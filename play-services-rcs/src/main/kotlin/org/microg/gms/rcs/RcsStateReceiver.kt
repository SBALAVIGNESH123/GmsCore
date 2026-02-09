/*
 * Copyright 2024-2026 microG Project Team
 * Licensed under Apache-2.0
 *
 * RcsStateReceiver - Broadcast receiver for system events
 * 
 * Listens for SIM state changes, boot completion, and network changes
 * to automatically manage RCS registration state.
 */

package org.microg.gms.rcs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class RcsStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RcsStateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        
        Log.d(TAG, "Received broadcast: $action")
        
        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                handleBootCompleted(context)
            }
            
            "android.intent.action.SIM_STATE_CHANGED" -> {
                handleSimStateChanged(context, intent)
            }
            
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                handleConnectivityChanged(context)
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        Log.d(TAG, "Boot completed, checking RCS state")
        
        // diamond-polish: Actually start the service if provisioned, don't just log it.
        val provisioningManager = RcsProvisioningManager(context)
        
        if (provisioningManager.isProvisioned()) {
            Log.i(TAG, "RCS was previously provisioned, starting RcsService to resume connection")
            startRcsService(context)
        } else {
             Log.d(TAG, "RCS not provisioned, waiting for user/app start")
        }
    }

    private fun handleSimStateChanged(context: Context, intent: Intent) {
        val simState = intent.getStringExtra("ss")
        Log.d(TAG, "SIM state changed: $simState")
        
        if ("READY" == simState) {
            Log.i(TAG, "SIM card is ready, ensuring RCS service is active")
            startRcsService(context)
        }
    }

    private fun startRcsService(context: Context) {
        try {
            val serviceIntent = Intent(context, RcsService::class.java)
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RCS service", e)
        }
    }

    private fun handleConnectivityChanged(context: Context) {
        val isNetworkAvailable = NetworkHelper.isNetworkAvailable(context)
        Log.d(TAG, "Network connectivity changed: available=$isNetworkAvailable")
    }
}
