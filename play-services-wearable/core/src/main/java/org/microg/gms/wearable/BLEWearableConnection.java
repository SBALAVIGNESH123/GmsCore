/*
 * Copyright (C) 2013-2019 microG Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.microg.gms.wearable;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import org.microg.wearable.WearableConnection;
import org.microg.wearable.proto.MessagePiece;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class BLEWearableConnection extends WearableConnection {
    private static final String TAG = "BLEWearConnection";
    private static final int MAX_PIECE_SIZE = 20 * 1024 * 1024; // 20MB limit
    private static final String PREFS_NAME = "BLEWearableConnection.Prefs";
    private static final String PREF_PAIRING_CONFIRMED = "pairing_confirmed";

    private static final UUID SERVICE_UUID = UUID.fromString("0000fcf1-0000-1000-8000-00805f9b34fb");
    private static final UUID WRITE_CHAR_UUID = UUID.fromString("07b18b65-9076-4050-a754-21adc1e12422");
    private static final UUID READ_CHAR_UUID = UUID.fromString("280adb07-4033-40c4-b2c0-8a3c75df4165");
    private static final UUID RECONNECT_CHAR_UUID = UUID.fromString("ffeddd90-74bc-11e4-82f8-0800200c9a66");
    private static final UUID RESET_CHAR_UUID = UUID.fromString("0eb6f3c9-df9c-4679-bcae-06ba51f7920f");
    private static final UUID PAIR_CONFIRM_CHAR_UUID = UUID.fromString("1699e0b2-357c-4c98-8007-7a50b5b3e771");
    private static final UUID DECOMMISSION_CHAR_UUID = UUID.fromString("65bbb4b0-c1c7-11e4-8dfc-aa07a5b093db");
    private static final UUID NOTIFY_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final BluetoothGatt gatt;
    private final String remoteAddress;

    // Characteristics
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic incomingCharacteristic;
    private BluetoothGattCharacteristic reconnectCharacteristic;
    private BluetoothGattCharacteristic resetCharacteristic;
    private BluetoothGattCharacteristic pairConfirmCharacteristic;
    private BluetoothGattCharacteristic decommissionCharacteristic;

    private volatile boolean connected = false;
    private final CountDownLatch connectionLatch = new CountDownLatch(1);

    // Buffers and queues
    private ByteArrayOutputStream incomingBuffer = new ByteArrayOutputStream();
    private final BlockingQueue<MessagePiece> incomingMessages = new LinkedBlockingQueue<>();
    private final Queue<byte[]> writeQueue = new ConcurrentLinkedQueue<>();
    private boolean isWriting = false;

    private static final MessagePiece POISON_PILL = new MessagePiece.Builder().build();
    private int mtu = 23; // Default BLE MTU

    private final AtomicBoolean latchReleased = new AtomicBoolean(false);
    private volatile boolean fullyInitialized = false;

    // Setup state machine
    private enum SetupState {
        CHECKING_PAIRING,
        CHECKING_DECOMMISSION,
        SUBSCRIBING_INCOMING,
        SUBSCRIBING_RECONNECT,
        SUBSCRIBING_RESET,
        SENDING_RESET_SIGNAL,
        COMPLETE
    }
    private volatile SetupState setupState = SetupState.CHECKING_PAIRING;

    private void releaseLatch() {
        if (latchReleased.compareAndSet(false, true))
            connectionLatch.countDown();
    }

    public boolean isFullyInitialized() {
        return this.fullyInitialized;
    }

    public BLEWearableConnection(BluetoothDevice device, Context context, Listener listener) throws IOException {
        super(listener);
        this.context = context;
        this.remoteAddress = device.getAddress();

        Log.d(TAG, "Creating BLE connection to " + device.getAddress());

        // Permission guard for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new IOException("BLUETOOTH_CONNECT permission missing");
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            this.gatt = device.connectGatt(context, false, gattCallback);
        }

        if (gatt == null) {
            throw new IOException("Failed to create GATT connection");
        }

        // Wait for connection with timeout
        try {
            if (!connectionLatch.await(30, TimeUnit.SECONDS)) {
                try { gatt.close(); } catch (Exception ignored) {}
                throw new IOException("BLE connection timeout");
            }
        } catch (InterruptedException e) {
            try { gatt.close(); } catch (Exception ignored) {}
            throw new IOException("BLE connection interrupted");
        }

        if (!connected) {
            throw new IOException("BLE connection failed");
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.d(TAG, "Connection state change: status=" + status + ", newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Connected to GATT, requesting MTU...");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    if (!g.requestMtu(83)) // MTU 83 as per Wear OS implementation
                        g.discoverServices();
                } else {
                    g.discoverServices();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT");
                connected = false;

                synchronized (writeQueue) {
                    writeQueue.clear();
                    isWriting = false;
                }

                if (fullyInitialized) {
                    incomingMessages.offer(POISON_PILL);
                }

                releaseLatch();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Log.d(TAG, "MTU changed: " + mtu + ", status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BLEWearableConnection.this.mtu = Math.max(20, mtu - 3);
            }
            g.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            Log.d(TAG, "Services discovered: status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed");
                g.disconnect();
                releaseLatch();
                return;
            }

            BluetoothGattService wearService = g.getService(SERVICE_UUID);

            if (wearService == null) {
                Log.e(TAG, "No Wear OS Clockwork service found. Available services:");
                for (BluetoothGattService service : g.getServices()) {
                    Log.d(TAG, "  - " + service.getUuid());
                }
                g.disconnect();
                releaseLatch();
                return;
            }

            // Get all characteristics
            writeCharacteristic = wearService.getCharacteristic(WRITE_CHAR_UUID);
            incomingCharacteristic = wearService.getCharacteristic(READ_CHAR_UUID);
            reconnectCharacteristic = wearService.getCharacteristic(RECONNECT_CHAR_UUID);
            resetCharacteristic = wearService.getCharacteristic(RESET_CHAR_UUID);
            pairConfirmCharacteristic = wearService.getCharacteristic(PAIR_CONFIRM_CHAR_UUID);
            decommissionCharacteristic = wearService.getCharacteristic(DECOMMISSION_CHAR_UUID);

            // Log what we found
            Log.d(TAG, "Characteristics found:");
            Log.d(TAG, "  Write: " + (writeCharacteristic != null));
            Log.d(TAG, "  Incoming: " + (incomingCharacteristic != null));
            Log.d(TAG, "  Reconnect: " + (reconnectCharacteristic != null));
            Log.d(TAG, "  Reset: " + (resetCharacteristic != null));
            Log.d(TAG, "  PairConfirm: " + (pairConfirmCharacteristic != null));
            Log.d(TAG, "  Decommission: " + (decommissionCharacteristic != null));

            // Check for required characteristics
            if (writeCharacteristic == null || incomingCharacteristic == null ||
                    pairConfirmCharacteristic == null || decommissionCharacteristic == null) {
                Log.e(TAG, "Missing required characteristics!");
                g.disconnect();
                releaseLatch();
                return;
            }

            // Start setup sequence: check pairing first
            setupState = SetupState.CHECKING_PAIRING;
            checkPairingConfirmation(g);
        }

        private void checkPairingConfirmation(BluetoothGatt g) {
            Log.d(TAG, "Checking pairing confirmation...");
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            if (!prefs.getBoolean(PREF_PAIRING_CONFIRMED, false)) {
                Log.d(TAG, "Pairing not confirmed, reading pairing characteristic...");
                if (!g.readCharacteristic(pairConfirmCharacteristic)) {
                    Log.e(TAG, "Failed to read pairing characteristic");
                    g.disconnect();
                    releaseLatch();
                }
            } else {
                Log.d(TAG, "Pairing already confirmed, checking decommission status...");
                setupState = SetupState.CHECKING_DECOMMISSION;
                checkDecommissionStatus(g);
            }
        }

        private void checkDecommissionStatus(BluetoothGatt g) {
            Log.d(TAG, "Checking decommission status...");
            if (!g.readCharacteristic(decommissionCharacteristic)) {
                Log.e(TAG, "Failed to read decommission characteristic");
                g.disconnect();
                releaseLatch();
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            UUID uuid = characteristic.getUuid();
            Log.d(TAG, "Characteristic read: " + uuid + " status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Read failed for " + uuid);
                g.disconnect();
                releaseLatch();
                return;
            }

            byte[] value = characteristic.getValue();
            if (value == null) {
                Log.w(TAG, "Read null value from " + uuid);
                g.disconnect();
                releaseLatch();
                return;
            }

            if (PAIR_CONFIRM_CHAR_UUID.equals(uuid)) {
                handlePairingConfirmationRead(g, value);
            } else if (DECOMMISSION_CHAR_UUID.equals(uuid)) {
                handleDecommissionRead(g, value);
            }
        }

        private void handlePairingConfirmationRead(BluetoothGatt g, byte[] value) {
            if (value.length == 1 && value[0] == 1) {
                Log.d(TAG, "Pairing confirmed!");
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putBoolean(PREF_PAIRING_CONFIRMED, true).apply();

                setupState = SetupState.CHECKING_DECOMMISSION;
                checkDecommissionStatus(g);
            } else {
                Log.w(TAG, "Invalid pairing confirmation value: " + Arrays.toString(value));
                g.disconnect();
                releaseLatch();
            }
        }

        private void handleDecommissionRead(BluetoothGatt g, byte[] value) {
            if (value.length != 1) {
                Log.w(TAG, "Invalid decommission value length: " + value.length);
                g.disconnect();
                releaseLatch();
                return;
            }

            if (value[0] == 1) {
                Log.w(TAG, "Watch needs to be decommissioned!");
                g.disconnect();
                releaseLatch();
                return;
            }

            Log.d(TAG, "Watch is not decommissioned, proceeding with setup");
            setupState = SetupState.SUBSCRIBING_INCOMING;
            enableNotification(g, incomingCharacteristic);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            UUID charUuid = descriptor.getCharacteristic().getUuid();
            Log.d(TAG, "Descriptor write: status=" + status + " for char=" + charUuid);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Failed to write descriptor");
                g.disconnect();
                releaseLatch();
                return;
            }

            // State machine for setup sequence
            switch (setupState) {
                case SUBSCRIBING_INCOMING:
                    if (READ_CHAR_UUID.equals(charUuid)) {
                        Log.d(TAG, "Incoming subscribed, subscribing to reconnect...");
                        setupState = SetupState.SUBSCRIBING_RECONNECT;
                        if (reconnectCharacteristic != null) {
                            enableNotification(g, reconnectCharacteristic);
                        } else {
                            // Skip to reset if reconnect not available
                            setupState = SetupState.SUBSCRIBING_RESET;
                            subscribeToResetOrComplete(g);
                        }
                    }
                    break;

                case SUBSCRIBING_RECONNECT:
                    if (RECONNECT_CHAR_UUID.equals(charUuid)) {
                        Log.d(TAG, "Reconnect subscribed, checking reset...");
                        setupState = SetupState.SUBSCRIBING_RESET;
                        subscribeToResetOrComplete(g);
                    }
                    break;

                case SUBSCRIBING_RESET:
                    if (RESET_CHAR_UUID.equals(charUuid)) {
                        Log.d(TAG, "Reset subscribed, sending reset signal...");
                        setupState = SetupState.SENDING_RESET_SIGNAL;
                        sendResetSignal(g);
                    }
                    break;
            }
        }

        private void subscribeToResetOrComplete(BluetoothGatt g) {
            if (resetCharacteristic != null) {
                Log.d(TAG, "Attempting to subscribe to reset characteristic");
                if (!enableNotification(g, resetCharacteristic)) {
                    Log.w(TAG, "Reset subscription failed, completing anyway");
                    completeSetup();
                }
            } else {
                Log.d(TAG, "No reset characteristic, completing setup");
                completeSetup();
            }
        }

        private void sendResetSignal(BluetoothGatt g) {
            if (resetCharacteristic == null) {
                completeSetup();
                return;
            }

            // Send MTU size as reset signal (mtu - 3 for ATT overhead)
            int mtuValue = mtu;
            Log.d(TAG, "Sending reset signal with MTU=" + mtuValue);

            byte[] resetValue = String.valueOf(mtuValue).getBytes();
            resetCharacteristic.setValue(resetValue);
            resetCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            if (!g.writeCharacteristic(resetCharacteristic)) {
                Log.w(TAG, "Failed to send reset signal");
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            UUID uuid = characteristic.getUuid();

            if (RESET_CHAR_UUID.equals(uuid)) {
                Log.d(TAG, "Reset signal sent: status=" + status);
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    completeSetup();
                } else {
                    Log.w(TAG, "Reset signal failed, completing anyway");
                    completeSetup();
                }
                return;
            }

            // Handle regular write queue
            synchronized (writeQueue) {
                isWriting = false;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    processWriteQueue();
                } else {
                    Log.w(TAG, "Write failed: " + status);
                    writeQueue.clear();
                }
            }
        }

        private void completeSetup() {
            setupState = SetupState.COMPLETE;
            Log.d(TAG, "BLE connection fully initialized!");
            connected = true;
            fullyInitialized = true;
            releaseLatch();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] chunk = characteristic.getValue();
            if (chunk == null || chunk.length == 0) return;

            Log.d(TAG, "Received " + chunk.length + " bytes on " + characteristic.getUuid());

            try {
                handleIncomingData(chunk);
            } catch (IOException e) {
                Log.w(TAG, "Error handling incoming data", e);
            }
        }
    };

    private boolean enableNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "Failed to enable notifications locally for " + characteristic.getUuid());
            return false;
        }

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(NOTIFY_DESCRIPTOR);
        if (descriptor != null) {
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            return gatt.writeDescriptor(descriptor);
        } else {
            Log.w(TAG, "Notification descriptor not found for " + characteristic.getUuid());
            return false;
        }
    }

    private void handleIncomingData(byte[] chunk) throws IOException {
        incomingBuffer.write(chunk);

        byte[] data = incomingBuffer.toByteArray();
        int offset = 0;

        while (data.length - offset >= 4) {
            int msgLen = ((data[offset] & 0xFF) << 24)
                    | ((data[offset + 1] & 0xFF) << 16)
                    | ((data[offset + 2] & 0xFF) << 8)
                    | (data[offset + 3] & 0xFF);

            if (msgLen < 0 || msgLen > MAX_PIECE_SIZE) {
                Log.w(TAG, "Invalid message length: " + msgLen + ", resetting buffer");
                incomingBuffer.reset();
                return;
            }

            if (data.length - offset < 4 + msgLen) {
                break;
            }

            byte[] pieceBytes = Arrays.copyOfRange(data, offset + 4, offset + 4 + msgLen);
            offset += 4 + msgLen;

            try {
                MessagePiece piece = MessagePiece.ADAPTER.decode(pieceBytes);
                incomingMessages.offer(piece);
            } catch (Exception e) {
                Log.w(TAG, "Failed to decode MessagePiece", e);
            }
        }

        if (offset == data.length) {
            incomingBuffer.reset();
        } else if (offset > 0) {
            incomingBuffer.reset();
            incomingBuffer.write(data, offset, data.length - offset);
        }
    }

    @Override
    protected void writeMessagePiece(MessagePiece piece) throws IOException {
        if (!connected) {
            throw new IOException("Not connected");
        }

        byte[] pieceBytes = piece.encode();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(pieceBytes.length);
        dos.write(pieceBytes);
        dos.flush();

        byte[] message = baos.toByteArray();

        int offset = 0;
        while (offset < message.length) {
            int chunkSize = Math.min(mtu, message.length - offset);
            byte[] chunk = Arrays.copyOfRange(message, offset, offset + chunkSize);
            writeQueue.offer(chunk);
            offset += chunkSize;
        }

        processWriteQueue();
    }

    private void processWriteQueue() {
        synchronized (writeQueue) {
            if (isWriting || writeQueue.isEmpty() || writeCharacteristic == null) {
                return;
            }

            byte[] chunk = writeQueue.poll();
            if (chunk == null) return;

            isWriting = true;
            writeCharacteristic.setValue(chunk);
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                Log.w(TAG, "Failed to queue write");
                isWriting = false;
            }
        }
    }

    @Override
    protected MessagePiece readMessagePiece() throws IOException {
        try {
            MessagePiece piece = incomingMessages.take();
            if (piece == POISON_PILL) {
                throw new IOException("Connection closed");
            }
            return piece;
        } catch (InterruptedException e) {
            throw new IOException("Read interrupted", e);
        }
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    @Override
    public void close() throws IOException {
        connected = false;
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void run() {
        super.run();
    }
}