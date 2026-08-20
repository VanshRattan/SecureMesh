package com.capstone.chatapp.data.transport

import android.util.Base64
import com.capstone.chatapp.data.model.Message
import com.capstone.chatapp.data.transport.ble.BlePacket
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * [Transport] over the internet (Firestore). Two Firestore shapes exist on the other side of
 * this interface: a shared `emergencies` collection for broadcasts ([Packet.destId] == null,
 * mirroring the BLE flood), and per-pair `chats/{chatId}/messages` for targeted 1:1 packets.
 * [chatId] is derived the same deterministic way both sides already agree on
 * ([Message.getChatId]), so this never needs to be told which chat a targeted packet belongs to.
 *
 * Targeted 1:1 packets: [Packet.payload] is AES-256-GCM ciphertext (see
 * `data/security/CryptoManager.kt`), stored as a base64 `ciphertext` field — Firestore never
 * sees plaintext for 1:1 messages. Broadcasts stay plaintext by design: an SOS is meant to be
 * readable by everyone reachable, so there is no single recipient to encrypt it for.
 */
class InternetTransport(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) : Transport {

    private val chats get() = firestore.collection("chats")
    private val emergencies get() = firestore.collection("emergencies")

    /** Broadcast SOS traffic only — targeted 1:1 packets are observed per-chat by ChatRepository. */
    override val incoming: Flow<Packet> = callbackFlow {
        val registration = emergencies
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                snapshot?.documents?.forEach { doc ->
                    val ble = BlePacket(
                        msgId = doc.id,
                        ttl = 0,
                        hopCount = 0,
                        reachCount = 0,
                        senderId = doc.getString("senderId") ?: "",
                        senderName = doc.getString("senderName") ?: "",
                        timestamp = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L,
                        text = doc.getString("text") ?: return@forEach,
                    )
                    trySend(ble.toPacket())
                }
            }
        awaitClose { registration.remove() }
    }

    override fun isAvailable(): Boolean = true

    override suspend fun sendToNextHop(packet: Packet, nextHop: String?, tier: Tier): SendResult = try {
        if (packet.destId == null) {
            sendBroadcast(packet)
        } else {
            sendTargeted(packet)
        }
        SendResult.SENT
    } catch (e: Exception) {
        SendResult.FAILED
    }

    private suspend fun sendBroadcast(packet: Packet) {
        val ble = requireNotNull(BlePacket.fromPacket(packet)) { "malformed emergency packet" }
        val data = hashMapOf(
            "senderId" to ble.senderId,
            "senderName" to ble.senderName,
            "text" to ble.text,
            "timestamp" to Timestamp.now(),
        )
        // msgId as the document id so the BLE and internet copies of the same SOS collide.
        emergencies.document(ble.msgId).set(data).await()
    }

    private suspend fun sendTargeted(packet: Packet) {
        val destId = requireNotNull(packet.destId) { "targeted packet missing destId" }
        val srcId = packet.srcId.orEmpty()
        val chatId = Message.getChatId(srcId, destId)
        // packet.payload is AES-256-GCM ciphertext (see CryptoManager) — Firestore/any relay
        // only ever sees this opaque blob, never plaintext. Base64 because Firestore string
        // fields must be valid UTF-16; raw bytes would corrupt on round-trip.
        val data = hashMapOf(
            "senderId" to srcId,
            "ciphertext" to Base64.encodeToString(packet.payload, Base64.NO_WRAP),
            "timestamp" to Timestamp.now(),
        )
        chats.document(chatId).collection("messages").add(data).await()
    }
}
