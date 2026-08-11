package com.capstone.chatapp.ui.emergency

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.chatapp.data.local.StoredSos
import com.capstone.chatapp.data.transport.ble.BleMeshService
import com.capstone.chatapp.data.transport.ble.BlePacket
import com.capstone.chatapp.di.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class EmergencyItem(
    val msgId: String,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val mine: Boolean,
    val hops: Int,
)

data class EmergencyUiState(
    val items: List<EmergencyItem> = emptyList(),
    val meshRunning: Boolean = false,
    val neighborCount: Int = 0,
    val online: Boolean = false,
    val bluetoothOn: Boolean = true,
    val permissionGranted: Boolean = false,
    val message: String? = null,
)

/**
 * Drives Emergency Mode: starts/stops the mesh foreground service, merges emergency
 * messages from the BLE mesh and the internet (Firestore) into one de-duplicated list,
 * and sends an SOS over BOTH paths when online.
 */
class EmergencyViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app.appContainer()
    private val mesh = container.bleMeshManager

    private val selfId: String = container.authRepository.currentUid.orEmpty()
    private var selfName: String = container.authRepository.currentEmail?.substringBefore('@') ?: "Someone"

    private val _state = MutableStateFlow(EmergencyUiState())
    val state: StateFlow<EmergencyUiState> = _state.asStateFlow()

    // msgIds already shown, so the BLE copy and the Firestore copy don't double up.
    private val seenIds = LinkedHashSet<String>()

    private val historyStore = container.emergencyHistoryStore

    init {
        // Prefer the saved username for the SOS sender label.
        viewModelScope.launch {
            runCatching { container.userRepository.getUser(selfId) }.getOrNull()?.let { user ->
                if (user.displayName.isNotBlank()) selfName = user.displayName
            }
        }
        // Load persisted SOS history first (covers offline-only BLE messages that
        // Firestore never saw), seeding seenIds so live copies don't duplicate them.
        viewModelScope.launch {
            val stored = runCatching { historyStore.load() }.getOrDefault(emptyList())
            if (stored.isNotEmpty()) {
                val items = stored.map { it.toItem() }.sortedBy { it.timestamp }
                items.forEach { seenIds.add(it.msgId) }
                _state.update { it.copy(items = items) }
            }
            observeMesh()
            observeInternet()
            observeNetwork()
        }
    }

    private fun observeMesh() {
        viewModelScope.launch {
            mesh.incoming.collect { packet -> addItem(packet, mine = packet.senderId == selfId) }
        }
        viewModelScope.launch {
            mesh.status.collect { status ->
                _state.update { it.copy(meshRunning = status.running, neighborCount = status.neighborCount) }
            }
        }
    }

    private fun observeInternet() {
        viewModelScope.launch {
            container.emergencyRepository.observeEmergencies().collect { packets ->
                packets.forEach { addItem(it, mine = it.senderId == selfId) }
            }
        }
    }

    private fun observeNetwork() {
        viewModelScope.launch {
            container.networkMonitor.isOnline.collect { online ->
                _state.update { it.copy(online = online) }
            }
        }
    }

    fun refreshPreconditions() {
        _state.update {
            it.copy(
                permissionGranted = com.capstone.chatapp.data.transport.ble.BlePermissions.allGranted(getApplication()),
                bluetoothOn = mesh.isBluetoothOn(),
            )
        }
    }

    fun startMesh() {
        if (!mesh.isBluetoothOn()) {
            _state.update { it.copy(message = "Please turn on Bluetooth to use Emergency Mode") }
            return
        }
        BleMeshService.start(getApplication(), selfId, selfName)
    }

    fun stopMesh() {
        BleMeshService.stop(getApplication())
    }

    fun sendSos(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val packet = BlePacket(
            msgId = UUID.randomUUID().toString(),
            ttl = com.capstone.chatapp.data.transport.ble.BleConstants.DEFAULT_TTL,
            hopCount = 0,
            reachCount = 1,
            senderId = selfId,
            senderName = selfName,
            timestamp = System.currentTimeMillis(),
            text = trimmed,
        )

        // Show it immediately in our own list.
        addItem(packet, mine = true)

        // BLE mesh (works with no internet).
        mesh.send(packet)

        // Internet path too, when available (same msgId -> receivers de-dup).
        if (_state.value.online) {
            viewModelScope.launch {
                runCatching { container.emergencyRepository.publish(packet) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun addItem(packet: BlePacket, mine: Boolean) {
        if (!seenIds.add(packet.msgId)) return
        val item = EmergencyItem(
            msgId = packet.msgId,
            senderName = if (mine) "You" else packet.senderName.ifBlank { "Someone" },
            text = packet.text,
            timestamp = packet.timestamp,
            mine = mine,
            hops = packet.hopCount,
        )
        // Oldest -> newest so the newest SOS sits at the bottom, like a chat.
        val updated = (_state.value.items + item).sortedBy { it.timestamp }
        _state.update { it.copy(items = updated) }
        // Persist so offline BLE messages survive an app restart.
        viewModelScope.launch {
            runCatching { historyStore.save(updated.map { it.toStored() }) }
        }
    }

    private fun StoredSos.toItem() = EmergencyItem(
        msgId = msgId,
        senderName = senderName,
        text = text,
        timestamp = timestamp,
        mine = mine,
        hops = hops,
    )

    private fun EmergencyItem.toStored() = StoredSos(
        msgId = msgId,
        senderName = senderName,
        text = text,
        timestamp = timestamp,
        mine = mine,
        hops = hops,
    )
}
