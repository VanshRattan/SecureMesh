package com.capstone.chatapp.data.repository

import com.capstone.chatapp.data.transport.InternetTransport
import com.capstone.chatapp.data.transport.SendResult
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.ble.BlePacket
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Internet path for emergency broadcasts: a shared Firestore `emergencies` collection
 * that all online devices publish to and listen on. When the device is online, an SOS
 * is sent here in addition to the BLE mesh (dual-send), so it reaches both nearby
 * offline phones and anyone online. Messages are keyed by the same msgId as the BLE
 * packet, so a device receiving both copies de-duplicates them.
 *
 * Sending wraps the SOS as a transport-agnostic [com.capstone.chatapp.data.transport.Packet]
 * and hands it to [InternetTransport] — this repository no longer talks to Firestore directly
 * for writes, only for the realtime read below.
 */
class EmergencyRepository(
    private val internetTransport: InternetTransport,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

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
        val result = internetTransport.sendToNextHop(packet.toPacket(), nextHop = null, tier = Tier.INTERNET)
        check(result == SendResult.SENT) { "Failed to publish emergency broadcast" }
    }
}
