/*
 * Copyright 2024-2026 microG Project Team
 * Licensed under Apache-2.0
 *
 * RcsPresenceManager - Presence and typing indicators
 */

package org.microg.gms.rcs.presence

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class RcsPresenceManager(private val context: Context) {

    companion object {
        private const val TAG = "RcsPresence"
        private const val TYPING_TIMEOUT_MS = 5000L
        private const val PRESENCE_REFRESH_MS = 60000L
    }

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val typingStates = ConcurrentHashMap<String, TypingState>()
    private val presenceStates = ConcurrentHashMap<String, PresenceInfo>()
    private val listeners = mutableListOf<PresenceListener>()
    
    private var ownPresence = PresenceStatus.AVAILABLE
    private var publishJob: Job? = null

    fun setOwnPresence(status: PresenceStatus, statusMessage: String? = null) {
        ownPresence = status
        
        coroutineScope.launch {
            publishPresence(status, statusMessage)
        }
    }

    private suspend fun publishPresence(status: PresenceStatus, message: String?) {
        Log.d(TAG, "Publishing presence: $status")
        notifyOwnPresenceChanged(status, message)
    }

    fun startTyping(conversationId: String) {
        val state = typingStates.getOrPut(conversationId) { TypingState(conversationId) }
        state.isTyping = true
        state.lastTypingTime = System.currentTimeMillis()
        
        coroutineScope.launch {
            sendTypingIndicator(conversationId, true)
            
            delay(TYPING_TIMEOUT_MS)
            
            val currentState = typingStates[conversationId]
            if (currentState != null && 
                System.currentTimeMillis() - currentState.lastTypingTime >= TYPING_TIMEOUT_MS) {
                stopTyping(conversationId)
            }
        }
    }

    fun stopTyping(conversationId: String) {
        typingStates[conversationId]?.isTyping = false
        
        coroutineScope.launch {
            sendTypingIndicator(conversationId, false)
        }
    }

    private val sipClient: org.microg.gms.rcs.sip.RcsSipClient by lazy {
        org.microg.gms.rcs.di.RcsServiceLocator.getSipClient()
    }

    private suspend fun sendTypingIndicator(conversationId: String, isTyping: Boolean) {
        Log.d(TAG, "Sending typing indicator: $isTyping for $conversationId")
        
        val contentType = "application/im-iscomposing+xml"
        val state = if (isTyping) "active" else "idle"
        val content = """
            <?xml version="1.0" encoding="UTF-8"?>
            <isComposing xmlns="urn:ietf:params:xml:ns:im-iscomposing"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="urn:ietf:params:xml:ns:im-iscomposing iscomposing.xsd">
                <state>$state</state>
                <contenttype>text/plain</contenttype>
                <refresh>60</refresh>
            </isComposing>
        """.trimIndent()
        
        // diamond-polish: Use actual SIP client to send the indicator, don't just log it.
        try {
            sipClient.sendMessage(conversationId, content, contentType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send typing indicator", e)
        }
    }

    fun onRemoteTypingReceived(phoneNumber: String, isTyping: Boolean) {
        listeners.forEach { it.onTypingStateChanged(phoneNumber, isTyping) }
    }

    fun onRemotePresenceReceived(phoneNumber: String, status: PresenceStatus, message: String?) {
        presenceStates[phoneNumber] = PresenceInfo(phoneNumber, status, message, System.currentTimeMillis())
        listeners.forEach { it.onPresenceChanged(phoneNumber, status, message) }
    }

    fun getPresence(phoneNumber: String): PresenceInfo? {
        return presenceStates[phoneNumber]
    }

    fun subscribeToPresence(phoneNumber: String) {
        coroutineScope.launch {
            Log.d(TAG, "Subscribing to presence for $phoneNumber")
        }
    }

    fun addListener(listener: PresenceListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: PresenceListener) {
        listeners.remove(listener)
    }

    private fun notifyOwnPresenceChanged(status: PresenceStatus, message: String?) {
        listeners.forEach { it.onOwnPresenceChanged(status, message) }
    }
}

data class TypingState(
    val conversationId: String,
    var isTyping: Boolean = false,
    var lastTypingTime: Long = 0
)

data class PresenceInfo(
    val phoneNumber: String,
    val status: PresenceStatus,
    val statusMessage: String?,
    val lastUpdated: Long
)

enum class PresenceStatus {
    AVAILABLE,
    AWAY,
    BUSY,
    DO_NOT_DISTURB,
    OFFLINE,
    UNKNOWN
}

interface PresenceListener {
    fun onTypingStateChanged(phoneNumber: String, isTyping: Boolean)
    fun onPresenceChanged(phoneNumber: String, status: PresenceStatus, message: String?)
    fun onOwnPresenceChanged(status: PresenceStatus, message: String?)
}
