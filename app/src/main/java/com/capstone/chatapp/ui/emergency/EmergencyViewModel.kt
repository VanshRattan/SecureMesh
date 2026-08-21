package com.capstone.chatapp.ui.emergency

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.chatapp.data.local.StoredSos
import com.capstone.chatapp.data.transport.SendResult
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.ble.BleConstants
import com.capstone.chatapp.data.transport.ble.BleLog
import com.capstone.chatapp.data.transport.ble.BleMeshService
import com.capstone.chatapp.data.transport.ble.BlePacket
import com.capstone.chatapp.data.transport.ble.BlePermissions
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

/**
 * What blocks the mesh right now, in the order the UI should resolve it. Each value maps
 * to exactly one user action, so the screen never has to guess which prompt to show.
 */
enum class MeshBlocker {
    NONE,
    NO_BLUETOOTH_HARDWARE,
    PERMISSIONS_MISSING,
    BLUETOOTH_OFF,
    LOCATION_SERVICE_OFF,
}

data class EmergencyUiState(
    val items: List<EmergencyItem> = emptyList(),
    val meshRunning: Boolean = false,
    val neighborCount: Int = 0,
    val online: Boolean = false,
    val bluetoothOn: Boolean = true,
    val bluetoothSupported: Boolean = true,
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val activeTier: Tier? = null,
    val bufferedCount: Int = 0,
    val permissionGranted: Boolean = false,
    val blocker: MeshBlocker = MeshBlocker.NONE,
    val meshError: String? = null,
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
    private val metricsCollector = container.metricsCollector

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
            observeActiveTier()
        }
    }

    /** Which tier the arbiter would currently use — the status badge's source of truth,
     * replacing the old plain online/offline flag. */
    private fun observeActiveTier() {
        viewModelScope.launch {
            container.transportSendCoordinator.activeTier.collect { tier ->
                _state.update { it.copy(activeTier = tier) }
            }
        }
        viewModelScope.launch {
            container.transportSendCoordinator.bufferedCount.collect { count ->
                _state.update { it.copy(bufferedCount = count) }
            }
        }
    }

    private fun observeMesh() {
        viewModelScope.launch {
            mesh.incoming.collect { packet -> addItem(packet, mine = packet.senderId == selfId, tier = Tier.BLE_MESH) }
        }
        viewModelScope.launch {
            mesh.status.collect { status ->
                _state.update {
                    it.copy(
                        meshRunning = status.running,
                        neighborCount = status.neighborCount,
                        advertising = status.advertising,
                        scanning = status.scanning,
                        meshError = status.lastError,
                    )
                }
            }
        }
    }

    private fun observeInternet() {
        viewModelScope.launch {
            container.emergencyRepository.observeEmergencies().collect { packets ->
                packets.forEach { addItem(it, mine = it.senderId == selfId, tier = Tier.INTERNET) }
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

    /**
     * Re-reads everything the mesh depends on. Called whenever the screen resumes,
     * because the user can change any of it from outside the app (quick settings,
     * app-info permissions, location toggle).
     */
    fun refreshPreconditions(): MeshBlocker {
        val context = getApplication<Application>()
        val supported = mesh.isBluetoothSupported()
        val permitted = BlePermissions.essentialsGranted(context)
        val btOn = mesh.isBluetoothOn()
        val locationOn = BlePermissions.isLocationServiceOn(context)

        val blocker = when {
            !supported -> MeshBlocker.NO_BLUETOOTH_HARDWARE
            // Permissions come before the Bluetooth-enable prompt: on Android 12+ we
            // cannot even ask the user to turn Bluetooth on without BLUETOOTH_CONNECT.
            !permitted -> MeshBlocker.PERMISSIONS_MISSING
            !btOn -> MeshBlocker.BLUETOOTH_OFF
            !locationOn -> MeshBlocker.LOCATION_SERVICE_OFF
            else -> MeshBlocker.NONE
        }

        BleLog.i(
            BleLog.Step.PRECHECK,
            "source" to "ui",
            "supported" to supported,
            "permitted" to permitted,
            "bluetoothOn" to btOn,
            "locationOn" to locationOn,
            "blocker" to blocker,
        )

        _state.update {
            it.copy(
                bluetoothSupported = supported,
                permissionGranted = permitted,
                bluetoothOn = btOn,
                blocker = blocker,
            )
        }
        return blocker
    }

    /**
     * Starts the mesh if nothing blocks it. Returns the blocker so the screen can launch
     * the matching system prompt; a message is only surfaced for things the user cannot
     * fix with a prompt.
     */
    fun startMeshIfReady(): MeshBlocker {
        val blocker = refreshPreconditions()
        when (blocker) {
            MeshBlocker.NONE -> {
                BleLog.i(BleLog.Step.SERVICE, "action" to "START", "source" to "EmergencyScreen")
                BleMeshService.start(getApplication(), selfId, selfName)
            }
            MeshBlocker.NO_BLUETOOTH_HARDWARE ->
                _state.update { it.copy(message = "This phone has no Bluetooth, so offline SOS is unavailable") }
            // The remaining blockers each have a system prompt the screen will launch.
            else -> Unit
        }
        return blocker
    }

    fun stopMesh() {
        BleMeshService.stop(getApplication())
    }

    fun onPermissionResult(granted: Map<String, Boolean>) {
        val essentialDenied = BlePermissions.essential().filter { granted[it] == false }
        if (essentialDenied.isNotEmpty()) {
            BleLog.w(BleLog.Step.PERMISSION, "denied" to BlePermissions.describe(essentialDenied))
            _state.update {
                it.copy(
                    message = "Emergency Mode needs Nearby devices permission to reach phones around you",
                )
            }
        } else {
            // A denied POST_NOTIFICATIONS must NOT stop the mesh — it only hides the
            // ongoing notification. Treating the whole array as all-or-nothing was
            // silently blocking the mesh on Android 13+.
            val notificationsDenied = BlePermissions.optional().any { granted[it] == false }
            BleLog.i(BleLog.Step.PERMISSION, "essential" to "granted", "notifications" to !notificationsDenied)
        }
    }

    fun sendSos(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val packet = BlePacket(
            msgId = UUID.randomUUID().toString(),
            ttl = BleConstants.DEFAULT_TTL,
            hopCount = 0,
            reachCount = 1,
            senderId = selfId,
            senderName = selfName,
            timestamp = System.currentTimeMillis(),
            text = trimmed,
        )

        BleLog.i(
            BleLog.Step.SOS_SEND,
            "source" to "ui",
            "msgId" to BleLog.shortId(packet.msgId),
            "online" to _state.value.online,
            "meshRunning" to _state.value.meshRunning,
            "neighbors" to _state.value.neighborCount,
        )

        // Show it immediately in our own list.
        addItem(packet, mine = true)

        // The arbiter fans a broadcast out across every reachable tier (internet, Wi-Fi
        // Direct, BLE mesh) instead of this ViewModel hard-coding which ones to try.
        viewModelScope.launch {
            val result = container.transportSendCoordinator.send(packet.toPacket())
            if (result == SendResult.QUEUED) {
                BleLog.w(BleLog.Step.SOS_SEND, "path" to "arbiter", "result" to "queued")
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun addItem(packet: BlePacket, mine: Boolean, tier: Tier? = null) {
        if (!seenIds.add(packet.msgId)) return
        // tier is only passed by the network-observing collectors (observeMesh/
        // observeInternet); the synchronous local echo in sendSos passes none, since that
        // call is this device's own optimistic UI update, not a network arrival -- recording
        // it would fabricate a zero-latency "delivery" of a message to itself.
        tier?.let { metricsCollector.recordReceive(packet.toPacket(), it) }
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
