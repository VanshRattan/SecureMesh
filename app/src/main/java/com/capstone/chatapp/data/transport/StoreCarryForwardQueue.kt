package com.capstone.chatapp.data.transport

import android.util.Base64
import com.capstone.chatapp.data.local.StoreCarryForwardStore
import com.capstone.chatapp.data.local.StoredScfEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val NORMAL_EXPIRY_MS = 15 * 60_000L        // 15 min -- a stale chat retry is worse than none
private const val EMERGENCY_EXPIRY_MS = 6 * 60 * 60_000L // 6 h -- a stranded SOS is worth holding much longer

/** A packet waiting for a bearer, with the wall-clock deadline past which it's dropped. */
data class QueuedPacket(val packet: Packet, val enqueuedAt: Long, val expiresAt: Long)

/**
 * Packets that failed on every currently-reachable tier. Persisted (via
 * [StoreCarryForwardStore]) so a queued SOS or chat message survives a process death, and
 * drained by [TransportSendCoordinator]'s retry tick whenever a bearer becomes reachable
 * again. Handover never re-encrypts or otherwise touches [Packet.payload] -- packets are
 * self-contained ciphertext (or the deliberately-plaintext SOS payload), so replaying one
 * through a different tier later is just moving the same bytes over a different wire.
 */
class StoreCarryForwardQueue(private val store: StoreCarryForwardStore) {

    private val mutex = Mutex()
    private var queue: MutableList<QueuedPacket>? = null // null until first load

    private suspend fun loaded(): MutableList<QueuedPacket> {
        queue?.let { return it }
        val fresh = store.load().mapNotNull { it.toQueuedPacket() }.toMutableList()
        queue = fresh
        return fresh
    }

    suspend fun enqueue(packet: Packet) {
        mutex.withLock {
            val q = loaded()
            val now = System.currentTimeMillis()
            val expiry = if (packet.priority == Priority.EMERGENCY) EMERGENCY_EXPIRY_MS else NORMAL_EXPIRY_MS
            // Replace any stale copy of the same message rather than piling up duplicates.
            q.removeAll { it.packet.msgId == packet.msgId }
            q.add(QueuedPacket(packet, now, now + expiry))
            persist(q)
        }
    }

    suspend fun remove(msgId: String) {
        mutex.withLock {
            val q = loaded()
            if (q.removeAll { it.packet.msgId == msgId }) persist(q)
        }
    }

    /** Drops anything past its expiry and returns what's left, oldest first. */
    suspend fun pending(): List<QueuedPacket> = mutex.withLock {
        val q = loaded()
        val now = System.currentTimeMillis()
        val before = q.size
        q.removeAll { it.expiresAt <= now }
        if (q.size != before) persist(q)
        q.sortedBy { it.enqueuedAt }
    }

    private suspend fun persist(q: List<QueuedPacket>) {
        store.save(q.map { it.toStored() })
    }

    private fun StoredScfEntry.toQueuedPacket(): QueuedPacket? {
        val bytes = runCatching { Base64.decode(packetB64, Base64.NO_WRAP) }.getOrNull() ?: return null
        val packet = Packet.deserialize(bytes) ?: return null
        return QueuedPacket(packet, enqueuedAt, expiresAt)
    }

    private fun QueuedPacket.toStored() = StoredScfEntry(
        packetB64 = Base64.encodeToString(packet.serialize(), Base64.NO_WRAP),
        enqueuedAt = enqueuedAt,
        expiresAt = expiresAt,
    )
}
