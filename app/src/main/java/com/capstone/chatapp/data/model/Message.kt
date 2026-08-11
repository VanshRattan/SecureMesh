package com.capstone.chatapp.data.model

import com.google.firebase.Timestamp

/**
 * A chat message. Matches Firestore structure: chats/{chatId}/messages/{messageId}.
 */
data class Message(
    val senderId: String = "",
    val text: String = "",
    val timestamp: Timestamp? = null,
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
