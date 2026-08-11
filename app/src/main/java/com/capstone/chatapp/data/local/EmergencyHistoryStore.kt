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

private val Context.sosDataStore: DataStore<Preferences> by preferencesDataStore(name = "sos_history")

/**
 * A single persisted SOS entry. Neutral record so persistence doesn't depend on the
 * UI layer's EmergencyItem — the ViewModel maps between the two.
 */
data class StoredSos(
    val msgId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val mine: Boolean,
    val hops: Int,
)

/**
 * Persists the emergency/SOS history via DataStore so offline BLE messages survive an
 * app restart (Firestore already reloads online SOS, but not the offline-only ones).
 * Stored as a JSON array under a single key.
 */
class EmergencyHistoryStore(private val context: Context) {

    private val key = stringPreferencesKey("sos_history_json")

    suspend fun load(): List<StoredSos> {
        val json = context.sosDataStore.data.first()[key] ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoredSos(
                    msgId = o.getString("msgId"),
                    senderName = o.getString("senderName"),
                    text = o.getString("text"),
                    timestamp = o.getLong("timestamp"),
                    mine = o.getBoolean("mine"),
                    hops = o.getInt("hops"),
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(items: List<StoredSos>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(
                JSONObject()
                    .put("msgId", s.msgId)
                    .put("senderName", s.senderName)
                    .put("text", s.text)
                    .put("timestamp", s.timestamp)
                    .put("mine", s.mine)
                    .put("hops", s.hops)
            )
        }
        context.sosDataStore.edit { prefs -> prefs[key] = arr.toString() }
    }
}
