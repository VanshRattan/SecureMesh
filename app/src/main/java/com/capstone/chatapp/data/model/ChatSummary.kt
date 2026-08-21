package com.capstone.chatapp.data.model

/**
 * A row in the Home conversation list: the other participant and the last message
 * exchanged. Built from the parent `chats/{chatId}` summary doc.
 */
data class ChatSummary(
    val peerUid: String = "",
    val peerName: String = "",
    val lastMessage: String = "",
    val timestamp: Long = 0L,
    /** Whether this contact's public key has been confirmed out-of-band (QR pairing or a
     * matching safety number) -- see `data/local/ContactSecurityStore.kt`. */
    val verified: Boolean = false,
)
