package com.capstone.chatapp.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.scfDataStore: DataStore<Preferences> by preferencesDataStore(name = "store_carry_forward")

/**
 * One queued packet, as bytes -- persistence doesn't need to understand the wire format,
 * `Packet.serialize()`/`Packet.deserialize()` already does. Base64 because DataStore
 * Preferences only stores strings.
 */
data class StoredScfEntry(
    val packetB64: String,
    val enqueuedAt: Long,
    val expiresAt: Long,
)

/**
 * Persists the store-carry-forward queue so a packet that couldn't go out over any tier
 * survives a process death and is still retried once a bearer comes back (see
 * `data/transport/StoreCarryForwardQueue.kt` and `TransportSendCoordinator`).
 */
class StoreCarryForwardStore(private val context: Context) {

    private val key = stringPreferencesKey("scf_queue_json")

    suspend fun load(): List<StoredScfEntry> {
        val json = context.scfDataStore.data.first()[key] ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoredScfEntry(
                    packetB64 = o.getString("packetB64"),
                    enqueuedAt = o.getLong("enqueuedAt"),
                    expiresAt = o.getLong("expiresAt"),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(entries: List<StoredScfEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("packetB64", e.packetB64)
                    .put("enqueuedAt", e.enqueuedAt)
                    .put("expiresAt", e.expiresAt)
            )
        }
        context.scfDataStore.edit { prefs -> prefs[key] = arr.toString() }
    }
}
