package com.capstone.chatapp.data.repository

import com.capstone.chatapp.data.model.ChatSummary
import com.capstone.chatapp.data.model.Message
import com.capstone.chatapp.data.transport.InternetTransport
import com.capstone.chatapp.data.transport.Packet
import com.capstone.chatapp.data.transport.Priority
import com.capstone.chatapp.data.transport.SendResult
import com.capstone.chatapp.data.transport.Tier
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Firestore access for chat messages: chats/{chatId}/messages/{messageId}.
 * Exposes messages as a cold Flow (wrapping the realtime snapshot listener) so
 * ViewModels can collect them without knowing about Firebase.
 *
 * Sending wraps the message as a transport-agnostic [Packet] (destId = peer, srcId = sender)
 * and hands it to [InternetTransport] for the actual message-doc write; the chat-summary
 * upsert below it is index bookkeeping for the Home list, not part of the wire packet, so it
 * stays a direct Firestore write here.
 */
class ChatRepository(
    private val internetTransport: InternetTransport,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    private val chats get() = firestore.collection("chats")

    private fun messagesRef(chatId: String) =
        chats.document(chatId).collection("messages")

    /** Realtime stream of messages for a chat, ordered oldest-first. */
    fun observeMessages(chatId: String): Flow<List<Message>> = callbackFlow {
        val registration = messagesRef(chatId)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val messages = snapshot?.documents?.mapNotNull { doc ->
                    val senderId = doc.getString("senderId") ?: return@mapNotNull null
                    val text = doc.getString("text") ?: return@mapNotNull null
                    Message(senderId, text, doc.getTimestamp("timestamp"))
                }.orEmpty()
                trySend(messages)
            }
        awaitClose { registration.remove() }
    }

    suspend fun sendMessage(
        chatId: String,
        senderId: String,
        senderName: String,
        peerId: String,
        peerName: String,
        text: String,
    ) {
        val packet = Packet(
            msgId = UUID.randomUUID().toString(),
            destId = peerId,
            srcId = senderId,
            ttl = 1,
            priority = Priority.NORMAL,
            tierTag = Tier.INTERNET,
            nonce = ByteArray(0),
            payload = text.toByteArray(Charsets.UTF_8),
        )
        val result = internetTransport.sendToNextHop(packet, nextHop = peerId, tier = Tier.INTERNET)
        check(result == SendResult.SENT) { "Failed to send message" }

        // Upsert the parent chat summary so both users can list this thread on Home.
        val now = Timestamp.now()
        val summary = hashMapOf(
            "participants" to listOf(senderId, peerId),
            "names" to mapOf(senderId to senderName, peerId to peerName),
            "lastMessage" to text,
            "lastTimestamp" to now,
        )
        chats.document(chatId).set(summary, SetOptions.merge()).await()
    }

    /** Realtime list of the current user's conversations, most recent first. */
    fun observeRecentChats(myUid: String): Flow<List<ChatSummary>> = callbackFlow {
        val registration = chats
            .whereArrayContains("participants", myUid)
            .orderBy("lastTimestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val summaries = snapshot?.documents?.mapNotNull { doc ->
                    @Suppress("UNCHECKED_CAST")
                    val participants = doc.get("participants") as? List<String> ?: return@mapNotNull null
                    val peerUid = participants.firstOrNull { it != myUid } ?: return@mapNotNull null
                    @Suppress("UNCHECKED_CAST")
                    val names = doc.get("names") as? Map<String, String> ?: emptyMap()
                    ChatSummary(
                        peerUid = peerUid,
                        peerName = names[peerUid]?.ifBlank { peerUid } ?: peerUid,
                        lastMessage = doc.getString("lastMessage") ?: "",
                        timestamp = doc.getTimestamp("lastTimestamp")?.toDate()?.time ?: 0L,
                    )
                }.orEmpty()
                trySend(summaries)
            }
        awaitClose { registration.remove() }
    }
}
