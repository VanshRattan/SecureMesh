package com.capstone.chatapp.data.model

import com.google.firebase.Timestamp

/**
 * Where a sent message currently stands. Only meaningful for the current user's own
 * outgoing messages -- an inbound message is DELIVERED by definition (this device already
 * has it). QUEUED/SENT reflect [com.capstone.chatapp.data.transport.TransportSendCoordinator]
 * and its store-carry-forward queue; DELIVERED means either it went out over Internet
 * (Firestore delivery is treated as immediate) or a delivery-receipt [Packet.ack] came back
 * from the peer over BLE/Wi-Fi Direct (see `OfflineMessageRouter`).
 */
enum class DeliveryState { QUEUED, SENT, DELIVERED }

/**
 * A chat message. Mirrors Firestore's chats/{chatId}/messages/{msgId} shape for online
 * history, and doubles as the local record for offline (BLE/Wi-Fi Direct) 1:1 messages —
 * see `data/local/OfflineMessageStore.kt` and `ChatRepository.observeMessages`, which merge
 * both sources by [msgId] into one thread.
 */
data class Message(
    val msgId: String = "",
    val senderId: String = "",
    val text: String = "",
    val timestamp: Timestamp? = null,
    val state: DeliveryState = DeliveryState.DELIVERED,
) {
    companion object {
        /**
         * Deterministic chatId for a one-to-one chat: both users get the same id
         * regardless of who starts the chat.
         */
        fun getChatId(userId1: String, userId2: String): String {
            return if (userId1 < userId2) "${userId1}_$userId2" else "${userId2}_$userId1"
        }
    }
}
