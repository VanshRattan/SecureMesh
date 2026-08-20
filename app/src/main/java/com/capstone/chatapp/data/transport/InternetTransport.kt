package com.capstone.chatapp.data.transport

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
 * The document fields are unchanged from before this abstraction existed (`senderId`, `text`,
 * `timestamp`, ...) — [Packet.payload] is UTF-8 text today. When end-to-end encryption lands,
 * payload becomes ciphertext and these fields switch to storing it opaquely; Firestore already
 * only ever sees what's in the packet, so relay-blindness is a schema change away, not an
 * architecture change.
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
        val data = hashMapOf(
            "senderId" to srcId,
            "text" to String(packet.payload, Charsets.UTF_8),
            "timestamp" to Timestamp.now(),
        )
        chats.document(chatId).collection("messages").add(data).await()
    }
}
