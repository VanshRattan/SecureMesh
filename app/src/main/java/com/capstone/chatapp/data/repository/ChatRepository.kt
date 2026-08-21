package com.capstone.chatapp.data.repository

import android.util.Base64
import com.capstone.chatapp.data.local.OfflineMessageStore
import com.capstone.chatapp.data.model.ChatSummary
import com.capstone.chatapp.data.model.DeliveryState
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Generic hop budget for a targeted 1:1 packet, independent of whichever tier ends up
 * carrying it -- Internet/Wi-Fi-Direct ignore it, and it lets a BLE-carried message
 * multi-hop through the mesh the same way an SOS does (see `BleTransport`). */
private const val TARGETED_TTL = 10

/**
 * Firestore access for chat messages: chats/{chatId}/messages/{messageId}, merged with any
 * offline (BLE/Wi-Fi Direct) history for the same chat kept in [offlineMessageStore] — see
 * `data/local/OfflineMessageStore.kt`'s doc comment for why that local copy exists at all.
 * Exposes messages as a cold Flow so ViewModels can collect them without knowing about
 * Firebase, transports, or which bearer actually carried a given message.
 *
 * Sending encrypts the text with [CryptoManager] (X25519 + HKDF + AES-256-GCM, see that class
 * for the full scheme), wraps the ciphertext as a transport-agnostic [Packet] (destId = peer,
 * srcId = sender) and hands it to [TransportSendCoordinator], which asks the per-hop arbiter
 * which tier to use — Internet, Wi-Fi Direct, or the BLE mesh, all now valid for a targeted
 * packet — and falls back to store-carry-forward if none is reachable right now. An optimistic
 * local echo is written to [offlineMessageStore] immediately (so the sender sees their own
 * message without waiting on Firestore, matching what a BLE/Wi-Fi-Direct-only send needs
 * anyway); the chat-summary Firestore upsert is index bookkeeping for the Home list, not part
 * of the wire packet, so it stays a direct (best-effort) Firestore write here.
 *
 * Receiving decrypts in [observeOnlineMessages] for Firestore-delivered messages; the
 * BLE/Wi-Fi-Direct receive path lives in `OfflineMessageRouter`, which decrypts, appends to
 * [offlineMessageStore], and sends a delivery-receipt [Packet.ack] back to the sender. Those
 * are the only two places a targeted packet's payload is ever opened — relays never call
 * [CryptoManager.decryptFrom].
 */
class ChatRepository(
    private val transportSendCoordinator: TransportSendCoordinator,
    private val peerKeyResolver: PeerKeyResolver,
    private val cryptoManager: CryptoManager,
    private val offlineMessageStore: OfflineMessageStore,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    private val chats get() = firestore.collection("chats")

    private fun messagesRef(chatId: String) =
        chats.document(chatId).collection("messages")

    /** Merged thread for [chatId]: Firestore (online) history plus any locally-held
     * offline (BLE/Wi-Fi Direct) messages, deduplicated by msgId. A message that this device
     * both echoed locally at send time *and* later saw arrive on Firestore (because the
     * arbiter picked Internet) keeps whichever copy has the more advanced [DeliveryState]. */
    fun observeMessages(chatId: String, myUid: String, peerId: String): Flow<List<Message>> =
        combine(observeOnlineMessages(chatId, myUid, peerId), offlineMessageStore.observe(chatId)) { online, offline ->
            (online + offline)
                .groupBy { it.msgId }
                .mapNotNull { (msgId, copies) -> if (msgId.isBlank()) null else copies.maxByOrNull { it.state.ordinal } }
                .sortedBy { it.timestamp?.toDate()?.time ?: 0L }
        }

    private fun observeOnlineMessages(chatId: String, myUid: String, peerId: String): Flow<List<Message>> = callbackFlow {
        // Resolved once per subscription, not per message: the derived key is static for the
        // pair as long as neither public key changes (see CryptoManager).
        val peerPubKey = runCatching { peerKeyResolver.resolve(myUid, peerId) }.getOrNull()

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
                    // Arrival in Firestore means it's already committed and visible to any
                    // listener -- treated as delivered (no separate Internet-tier ack).
                    Message(msgId = doc.id, senderId = senderId, text = text, timestamp = doc.getTimestamp("timestamp"), state = DeliveryState.DELIVERED)
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
        val peerPubKey = peerKeyResolver.resolve(senderId, peerId)
        val ciphertext = cryptoManager.encryptFor(peerId, peerPubKey, text.toByteArray(Charsets.UTF_8))

        val msgId = UUID.randomUUID().toString()
        val packet = Packet(
            msgId = msgId,
            destId = peerId,
            srcId = senderId,
            ttl = TARGETED_TTL,
            priority = Priority.NORMAL,
            tierTag = Tier.INTERNET,
            nonce = ByteArray(0), // AesGcmJce bundles its own random IV inside the payload
            payload = ciphertext,
        )

        // Optimistic echo so the sender sees their own message immediately regardless of
        // which tier ends up carrying it -- essential for BLE/Wi-Fi Direct, where there is no
        // Firestore snapshot to fall back on.
        val sentAt = Timestamp.now()
        offlineMessageStore.append(chatId, Message(msgId, senderId, text, sentAt, DeliveryState.QUEUED))

        val result = transportSendCoordinator.send(packet)
        val finalState = when {
            result != SendResult.SENT -> DeliveryState.QUEUED
            transportSendCoordinator.activeTier.value == Tier.INTERNET -> DeliveryState.DELIVERED
            else -> DeliveryState.SENT
        }
        offlineMessageStore.updateState(chatId, msgId, finalState)

        // Upsert the parent chat summary so both users can list this thread on Home. No
        // plaintext preview here on purpose: this doc is readable by anyone the Firestore rules
        // allow, so the "last message" field must stay generic to keep the relay blind.
        // Best-effort: a fully-offline (BLE-only) device has no Firestore connection to write
        // this to at all, and that must not fail the send -- the summary just catches up once
        // Firestore's own offline persistence syncs, or on the next online send.
        runCatching {
            val summary = hashMapOf(
                "participants" to listOf(senderId, peerId),
                "names" to mapOf(senderId to senderName, peerId to peerName),
                "lastMessage" to "🔒 New message",
                "lastTimestamp" to sentAt,
            )
            chats.document(chatId).set(summary, SetOptions.merge()).await()
        }
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
