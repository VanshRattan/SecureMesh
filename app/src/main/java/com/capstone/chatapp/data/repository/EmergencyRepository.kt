package com.capstone.chatapp.data.repository

import com.capstone.chatapp.data.transport.ble.BlePacket
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Internet-side read for emergency broadcasts: a shared Firestore `emergencies` collection
 * that all online devices listen on. Sending an SOS no longer goes through this repository
 * -- `EmergencyViewModel` hands the packet straight to
 * `com.capstone.chatapp.data.transport.TransportSendCoordinator`, which fans it out across
 * every reachable tier (internet, Wi-Fi Direct, BLE mesh) instead of this repository
 * hard-coding a BLE-plus-Firestore dual-send. Messages are keyed by the same msgId as the
 * BLE packet, so a device receiving copies over multiple tiers de-duplicates them.
 */
class EmergencyRepository(
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
}
