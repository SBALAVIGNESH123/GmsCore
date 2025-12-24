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

import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;

import org.microg.wearable.WearableConnection;
import org.microg.wearable.proto.MessagePiece;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class BLEWearableConnection extends WearableConnection {
    private static final String TAG = "BLEWearConnection";
    private static final int MAX_PIECE_SIZE = 20 * 1024 * 1024; // 20MB limit

    private static final UUID SERVICE_UUID = UUID.fromString("1c81447b-f48d-4d17-a606-3ff36527045a");
    private static final UUID READ_CHAR_UUID = UUID.fromString("6fcfb474-ce57-48ff-a4ce-b43767d6d04a");
    private static final UUID WRITE_CHAR_UUID = UUID.fromString("b2ae0493-d87f-475c-b656-5840e0a13fc8");
    private static final UUID NOTIFY_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final BluetoothGatt gatt;
    private final String remoteAddress;

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

    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic readCharacteristic;

    private static final byte BLE_BOND_MASK = 1;
    private static final byte CLASSIC_BOND_MASK = 2;

    private volatile String disconnectReason = "Unknown";

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
                throw new IOException("BLE connection timeout: " + disconnectReason);
            }
        } catch (InterruptedException e) {
            try { gatt.close(); } catch (Exception ignored) {}
            throw new IOException("BLE connection interrupted");
        }

        if (!connected) {
            throw new IOException("BLE connection failed: " + disconnectReason);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.d(TAG, "onConnectionStateChange: status=" + status + ", newState=" + newState);

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                boolean services = g.discoverServices();
                Log.d(TAG, "Discrovering services: " + services);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT");
                String reason = getDisconnectReason(status);
                Log.w(TAG, "BLE disconnected: " + reason + " (status=0x" + Integer.toHexString(status) + ")");
                disconnectReason = reason;

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
            Log.d(TAG, "onMtuChanged: mtu=" + mtu + ", status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BLEWearableConnection.this.mtu = Math.max(20, mtu - 3);
                Log.d(TAG, "Effective MTU set to: " + BLEWearableConnection.this.mtu);
            }
            boolean discovering = g.discoverServices();
            Log.d(TAG, "Service discovery initiated after MTU: " + discovering);
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            Log.d(TAG, "Services discovered: status=" + status);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Service discovery failed: " + status);
                disconnectReason = "Service discovery failed (0x" + Integer.toHexString(status) + ")";
                g.disconnect();
                releaseLatch();
                return;
            }

            BluetoothGattService wearService = g.getService(SERVICE_UUID);

            if (wearService == null) {
                Log.e(TAG, "No Wear OS Clockwork service found. Available services:");
                disconnectReason = "Clockwork service not found";
                for (BluetoothGattService service : g.getServices()) {
                    Log.d(TAG, "  - " + service.getUuid());
                }
                g.disconnect();
                releaseLatch();
                return;
            }

            BluetoothGattCharacteristic characteristic = wearService.getCharacteristic(READ_CHAR_UUID);

            if (characteristic == null) return;

            g.readCharacteristic(characteristic);

            // Get all characteristics
            writeCharacteristic = wearService.getCharacteristic(WRITE_CHAR_UUID);
            readCharacteristic = wearService.getCharacteristic(READ_CHAR_UUID);

            // Log what we found
            Log.d(TAG, "Characteristics found:");
            Log.d(TAG, "  Write: " + (writeCharacteristic != null));
            Log.d(TAG, "  Incoming: " + (readCharacteristic != null));

            // Check for required characteristics
            if (writeCharacteristic == null || readCharacteristic == null) {
                Log.e(TAG, "Missing required characteristics!");
                disconnectReason = "Missing required characteristics";
                g.disconnect();
                releaseLatch();
                return;
            }

            if (!g.readCharacteristic(readCharacteristic)) {
                Log.e(TAG, "Failed to read pairing status");
                disconnectReason = "Failed to read pairing status";
                g.disconnect();
                releaseLatch();
            }

        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            UUID uuid = characteristic.getUuid();
            Log.d(TAG, "onCharacteristicRead: uuid=" + uuid + ", status=" + status);;

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Read failed for " + uuid);
                disconnectReason = "Read failed (0x" + Integer.toHexString(status) + ")";
                g.disconnect();
                releaseLatch();
                return;
            }

            if (!READ_CHAR_UUID.equals(uuid)) {
                Log.e(TAG, "READ_CHAR_UUID is fail: current uuid = " + uuid);
                disconnectReason = "Read failed (0x" + Integer.toHexString(status) + "), UUID";
                g.disconnect();
                releaseLatch();
                return;
            }

            byte[] value = characteristic.getValue();
            if (value == null || value.length == 0) {
                Log.w(TAG, "Read null/empty value from pairing characteristic");
                disconnectReason = "Empty pairing status";
                g.disconnect();
                releaseLatch();
                return;
            }

            byte bondStatus = value[0];
            boolean bleBonded = (bondStatus & BLE_BOND_MASK) != 0;
            boolean classicBonded = (bondStatus & CLASSIC_BOND_MASK) != 0;
            Log.d(TAG, "Bond status: 0x" + Integer.toHexString(bondStatus & 0xFF) +
                    " (BLE=" + bleBonded + ", Classic=" + classicBonded + ")");

            if (!enableNotification(g, readCharacteristic)) {
                Log.e(TAG, "Failed to enable notifications");
                disconnectReason = "Failed to enable notifications";
                g.disconnect();
                releaseLatch();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            UUID charUuid = descriptor.getCharacteristic().getUuid();
            Log.d(TAG, "Descriptor write: status=" + status + " for char=" + charUuid);

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Failed to write descriptor");
                disconnectReason = "Descriptor write failed (0x" + Integer.toHexString(status) + ")";
                g.disconnect();
                releaseLatch();
                return;
            }

            if (!READ_CHAR_UUID.equals(charUuid)) {
                Log.w(TAG, "Failed to write descriptor");
                disconnectReason = "Descriptor write failed (0x" + Integer.toHexString(status) + "), UUID";
                g.disconnect();
                releaseLatch();
                return;
            }

            sendCapabilities(g);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            UUID uuid = characteristic.getUuid();
            Log.d(TAG, "onCharacteristicWrite: uuid=" + uuid + ", status=" + status);

            if (WRITE_CHAR_UUID.equals(uuid)) {
                Log.d(TAG, "Reset signal sent: status=" + status);
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Capabilities send failed: 0x" + Integer.toHexString(status) + ", continuing anyway");
                }
                completeSetup();
                return;
            }

            // Handle regular write queue
            synchronized (writeQueue) {
                isWriting = false;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    processWriteQueue();
                } else {
                    Log.w(TAG, "Write failed: 0x" + Integer.toHexString(status));
                    writeQueue.clear();
                }
            }
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


    private String getDisconnectReason(int status) {
        switch (status) {
            case BluetoothGatt.GATT_SUCCESS: return "GATT_SUCCESS";
            case BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION: return "GATT_INSUFFICIENT_AUTHORIZATION";
            case BluetoothGatt.GATT_CONNECTION_TIMEOUT: return "GATT_CONNECTION_TIMEOUT";
            case BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH: return "GATT_INVALID_ATTRIBUTE_LENGTH";
            case BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION: return "GATT_INSUFFICIENT_AUTHENTICATION";
            default: return "Error : " + status;
        }
    }

    private boolean enableNotification(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "Failed to enable notifications locally for " + characteristic.getUuid());
            return false;
        }

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(NOTIFY_DESCRIPTOR);
        if (descriptor == null) {
            Log.w(TAG, "Notification descriptor not found for " + characteristic.getUuid());
            return false;
        }

        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean written = gatt.writeDescriptor(descriptor);
        Log.d(TAG, "Notification descriptor write initiated: " + written);
        return written;
    }

    private void sendCapabilities(BluetoothGatt gatt) {
        try {
            byte[] capabilitiesProto = createCapabilitiesProto();

            ByteBuffer buffer = ByteBuffer.allocate(2 + capabilitiesProto.length);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putShort((short) capabilitiesProto.length);
            buffer.put(capabilitiesProto);

            byte[] data = buffer.array();

            Log.d(TAG, "Sending capabilities: " + data.length + " bytes");

            writeCharacteristic.setValue(data);
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);

            if (!gatt.writeCharacteristic(writeCharacteristic)) {
                Log.e(TAG, "Failed to initiate capabilities write");
                completeSetup(); // Continue anyway
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send capabilities", e);
            completeSetup(); // Continue anyway
        }
    }

    private void completeSetup() {
        Log.d(TAG, "BLE connection fully initialized!");
        connected = true;
        fullyInitialized = true;
        releaseLatch();
    }

    private byte[] createCapabilitiesProto() {
        // for now placeholder
        // need create valid PairingCapabilities$WearPairingCapabilities proto message
        return new byte[0];
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
        if (!connected || !fullyInitialized) {
            throw new IOException("Not connected or not fully initialized");
        }

        byte[] pieceBytes = piece.encode();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeInt(pieceBytes.length);
        dos.write(pieceBytes);
        dos.flush();

        byte[] message = baos.toByteArray();
        Log.d(TAG, "Writing MessagePiece: " + message.length + " bytes total");

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
            if (isWriting || writeQueue.isEmpty() || writeCharacteristic == null || !fullyInitialized) {
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
                writeQueue.clear();
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