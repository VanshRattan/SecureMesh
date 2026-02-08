package com.capstone.chatapp.model

import com.google.firebase.Timestamp

/**
 * Data class representing a chat message.
 * Matches Firestore structure: chats/{chatId}/messages/{messageId}
 */
data class Message(
    val senderId: String = "",
    val text: String = "",
    val timestamp: Timestamp? = null
) {
    companion object {
        /**
         * Creates deterministic chatId for one-to-one chat.
         * Both users get same chatId regardless of who starts the chat.
         */
        fun getChatId(userId1: String, userId2: String): String {
            return if (userId1 < userId2) {
                "${userId1}_$userId2"
            } else {
                "${userId2}_$userId1"
            }
        }
    }
}
