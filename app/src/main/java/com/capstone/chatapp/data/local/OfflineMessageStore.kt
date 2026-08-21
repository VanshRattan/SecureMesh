package com.capstone.chatapp.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.capstone.chatapp.data.model.DeliveryState
import com.capstone.chatapp.data.model.Message
import com.google.firebase.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private val Context.offlineMessagesDataStore: DataStore<Preferences> by preferencesDataStore(name = "offline_messages")

/**
 * Local record of 1:1 messages that travelled over BLE/Wi-Fi Direct — there is no Firestore
 * doc backing them, so unlike online history they would otherwise vanish on app restart or
 * once a newer message pushes them out of the mesh's own transient state. Decrypted plaintext
 * is stored here deliberately: this is on-device storage, not a relay, so it carries the same
 * trust boundary as showing the text on screen already does (see `ChatRepository`'s doc
 * comment on relay-blindness — that property is about what Firestore/BLE/Wi-Fi-Direct *relays*
 * see, not what the two endpoints store locally).
 *
 * One JSON blob per chatId, same pattern as [EmergencyHistoryStore]/[StoreCarryForwardStore].
 * [ChatRepository] merges this with the Firestore-backed online history by msgId.
 */
class OfflineMessageStore(private val context: Context) {

    private val mutex = Mutex()

    private fun key(chatId: String) = stringPreferencesKey("offline_msgs_$chatId")

    /** Reactive local history for [chatId] — recomposes whenever [append]/[updateState] write. */
    fun observe(chatId: String): Flow<List<Message>> =
        context.offlineMessagesDataStore.data.map { prefs -> parse(prefs[key(chatId)]) }

    /** Adds or replaces (by msgId) a message in [chatId]'s local history. */
    suspend fun append(chatId: String, message: Message) = mutex.withLock {
        val current = parse(context.offlineMessagesDataStore.data.first()[key(chatId)]).toMutableList()
        current.removeAll { it.msgId == message.msgId }
        current.add(message)
        save(chatId, current)
    }

    /** Bumps a previously-stored message's delivery state (e.g. on receiving its ack). */
    suspend fun updateState(chatId: String, msgId: String, state: DeliveryState) = mutex.withLock {
        val current = parse(context.offlineMessagesDataStore.data.first()[key(chatId)])
        val updated = current.map { if (it.msgId == msgId) it.copy(state = state) else it }
        save(chatId, updated)
    }

    private suspend fun save(chatId: String, messages: List<Message>) {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(
                JSONObject()
                    .put("msgId", m.msgId)
                    .put("senderId", m.senderId)
                    .put("text", m.text)
                    .put("timestampMillis", m.timestamp?.toDate()?.time ?: 0L)
                    .put("state", m.state.name)
            )
        }
        context.offlineMessagesDataStore.edit { it[key(chatId)] = arr.toString() }
    }

    private fun parse(json: String?): List<Message> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Message(
                    msgId = o.getString("msgId"),
                    senderId = o.getString("senderId"),
                    text = o.getString("text"),
                    timestamp = Timestamp(java.util.Date(o.getLong("timestampMillis"))),
                    state = runCatching { DeliveryState.valueOf(o.getString("state")) }.getOrDefault(DeliveryState.DELIVERED),
                )
            }
        }.getOrDefault(emptyList())
    }
}
