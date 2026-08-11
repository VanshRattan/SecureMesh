package com.capstone.chatapp.data.repository

import com.capstone.chatapp.data.transport.ble.BlePacket
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Internet path for emergency broadcasts: a shared Firestore `emergencies` collection
 * that all online devices publish to and listen on. When the device is online, an SOS
 * is sent here in addition to the BLE mesh (dual-send), so it reaches both nearby
 * offline phones and anyone online. Messages are keyed by the same msgId as the BLE
 * packet, so a device receiving both copies de-duplicates them.
 */
class EmergencyRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    private val collection get() = firestore.collection("emergencies")

    /** Recent emergency broadcasts, newest first, from the last [limit] entries. */
    fun observeEmergencies(limit: Long = 100): Flow<List<BlePacket>> = callbackFlow {
        val registration = collection
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                val packets = snapshot?.documents?.mapNotNull { doc ->
                    BlePacket(
                        msgId = doc.id,
                        ttl = 0,
                        hopCount = 0,
                        reachCount = 0,
                        senderId = doc.getString("senderId") ?: "",
                        senderName = doc.getString("senderName") ?: "",
                        timestamp = doc.getTimestamp("timestamp")?.toDate()?.time ?: 0L,
                        text = doc.getString("text") ?: return@mapNotNull null,
                    )
                }.orEmpty()
                trySend(packets)
            }
        awaitClose { registration.remove() }
    }

    suspend fun publish(packet: BlePacket) {
        val data = hashMapOf(
            "senderId" to packet.senderId,
            "senderName" to packet.senderName,
            "text" to packet.text,
            "timestamp" to Timestamp.now(),
        )
        // Use msgId as the document id so BLE and internet copies collide (de-dup).
        collection.document(packet.msgId).set(data).await()
    }
}
