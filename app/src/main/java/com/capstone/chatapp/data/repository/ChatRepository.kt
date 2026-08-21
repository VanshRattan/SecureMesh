package com.capstone.chatapp.data.repository

import android.util.Base64
import com.capstone.chatapp.data.local.ContactSecurityStore
import com.capstone.chatapp.data.model.ChatSummary
import com.capstone.chatapp.data.model.Message
import com.capstone.chatapp.data.security.CryptoManager
import com.capstone.chatapp.data.transport.Packet
import com.capstone.chatapp.data.transport.Priority
import com.capstone.chatapp.data.transport.SendResult
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.TransportSendCoordinator
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
 * Sending encrypts the text with [CryptoManager] (X25519 + HKDF + AES-256-GCM, see that class
 * for the full scheme), wraps the ciphertext as a transport-agnostic [Packet] (destId = peer,
 * srcId = sender) and hands it to [TransportSendCoordinator], which asks the per-hop arbiter
 * which tier to use and falls back to store-carry-forward if none is reachable right now; the
 * chat-summary upsert below it is index bookkeeping for the Home list, not part of the wire
 * packet, so it stays a direct Firestore write here. The summary deliberately carries no
 * plaintext preview — see the comment in [sendMessage] — so Firestore never sees 1:1 message
 * content at all.
 *
 * Receiving decrypts in [observeMessages], which is the only place a targeted packet's payload
 * is ever opened — relays (Firestore, and later BLE/Wi-Fi-Direct for offline 1:1) never call
 * [CryptoManager.decryptFrom].
 */
class ChatRepository(
    private val transportSendCoordinator: TransportSendCoordinator,
    private val userRepository: UserRepository,
    private val cryptoManager: CryptoManager,
    private val contactSecurityStore: ContactSecurityStore,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    private val chats get() = firestore.collection("chats")

    private fun messagesRef(chatId: String) =
        chats.document(chatId).collection("messages")

    /**
     * Resolves + caches [peerId]'s published public key: the local cache first (works offline,
     * and is how a QR-paired contact's key is found before they've ever been fetched online),
     * falling back to their Firestore profile. Throws if neither has it — meaning the peer has
     * never logged in since encryption shipped, so there is nothing to encrypt to yet.
     */
    private suspend fun resolvePeerPublicKey(myUid: String, peerId: String): String {
        contactSecurityStore.getCachedPeerPublicKey(myUid, peerId)?.let { return it }
        val fetched = userRepository.getUser(peerId)?.pubKey
        require(!fetched.isNullOrBlank()) { "This contact hasn't set up encryption yet" }
        contactSecurityStore.cachePeerPublicKey(myUid, peerId, fetched)
        return fetched
    }

    /** Realtime stream of messages for a chat, ordered oldest-first, decrypted for display. */
    fun observeMessages(chatId: String, myUid: String, peerId: String): Flow<List<Message>> = callbackFlow {
        // Resolved once per subscription, not per message: the derived key is static for the
        // pair as long as neither public key changes (see CryptoManager).
        val peerPubKey = runCatching { resolvePeerPublicKey(myUid, peerId) }.getOrNull()

        val registration = messagesRef(chatId)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val messages = snapshot?.documents?.mapNotNull { doc ->
                    val senderId = doc.getString("senderId") ?: return@mapNotNull null
                    val cipherB64 = doc.getString("ciphertext") ?: return@mapNotNull null
                    val text = peerPubKey?.let { pub ->
                        runCatching {
                            val bytes = Base64.decode(cipherB64, Base64.NO_WRAP)
                            String(cryptoManager.decryptFrom(peerId, pub, bytes), Charsets.UTF_8)
                        }.getOrNull()
                    } ?: "🔒 Unable to decrypt this message"
                    Message(senderId, text, doc.getTimestamp("timestamp"))
                }.orEmpty()
                trySend(messages)
            }
        awaitClose { registration.remove() }
    }

    /**
     * Returns [SendResult.SENT] or [SendResult.QUEUED] — a message that can't reach any tier
     * right now is queued for store-carry-forward, not a thrown error; the caller should tell
     * the user it'll go out automatically rather than treating it as a failure.
     */
    suspend fun sendMessage(
        chatId: String,
        senderId: String,
        senderName: String,
        peerId: String,
        peerName: String,
        text: String,
    ): SendResult {
        val peerPubKey = resolvePeerPublicKey(senderId, peerId)
        val ciphertext = cryptoManager.encryptFor(peerId, peerPubKey, text.toByteArray(Charsets.UTF_8))

        val packet = Packet(
            msgId = UUID.randomUUID().toString(),
            destId = peerId,
            srcId = senderId,
            ttl = 1,
            priority = Priority.NORMAL,
            tierTag = Tier.INTERNET,
            nonce = ByteArray(0), // AesGcmJce bundles its own random IV inside the payload
            payload = ciphertext,
        )
        val result = transportSendCoordinator.send(packet)

        // Upsert the parent chat summary so both users can list this thread on Home. No
        // plaintext preview here on purpose: this doc is readable by anyone the Firestore rules
        // allow, so the "last message" field must stay generic to keep the relay blind. Upserted
        // even when queued -- the SCF retry will actually deliver it, this is just the Home
        // list's index, not a delivery receipt.
        val now = Timestamp.now()
        val summary = hashMapOf(
            "participants" to listOf(senderId, peerId),
            "names" to mapOf(senderId to senderName, peerId to peerName),
            "lastMessage" to "🔒 New message",
            "lastTimestamp" to now,
        )
        chats.document(chatId).set(summary, SetOptions.merge()).await()
        return result
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
