package com.capstone.chatapp.data.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import androidx.core.content.ContextCompat
import com.capstone.chatapp.data.transport.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Snapshot of what the Wi-Fi Direct tier is doing. Mirrors
 * [com.capstone.chatapp.data.transport.ble.BleMeshManager]'s `MeshStatus` for the same
 * reason: the individual fields matter for debugging a real device far more than a single
 * "connected" boolean would.
 */
data class WifiDirectStatus(
    val available: Boolean = false,
    val discovering: Boolean = false,
    val groupFormed: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    val connectedSockets: Int = 0,
    val lastError: String? = null,
)

/**
 * Wi-Fi Direct tier: peer discovery, group formation, and a socket-based relay of
 * [Packet] bytes over the formed group.
 *
 * **Topology, and the single-group constraint this session targets.** Stock Android's
 * Wi-Fi Direct framework only ever gives an app *one* active group at a time, and that
 * group is a star: every client's traffic physically flows through the Group Owner (GO),
 * which the framework always assigns the fixed address [WifiDirectConstants.GROUP_OWNER_HOST].
 * There is no API for a phone to be a GO of one group and a client of another
 * simultaneously, so multi-group multi-hop (the general mesh case) is out of scope here —
 * see "Path to multi-hop" below.
 *
 * Within that one group, this class makes the star behave like the BLE tier's flood for a
 * single hop: the GO runs a [ServerSocket], accepts a socket per connecting client, and on
 * receiving a packet from one client relays it verbatim to every *other* connected client
 * ([handleIncomingFrame]) as well as delivering it locally via [incoming]. A non-GO client
 * holds one long-lived socket to the GO and both sends and receives over it. Which role a
 * given device ends up with is decided by the platform during [connectToPeer] (GO-intent
 * negotiation) and is not requested here.
 *
 * **Path to multi-group multi-hop (future work, not implemented):** the [Packet] header
 * already carries `ttl`/`destId`/`srcId` independent of the transport, so a future arbiter
 * could run several `WifiDirectManager`-like instances against different peers (Android 10+
 * exposes `WifiP2pManager.requestDeviceInfo`/multiple frequencies, but true concurrent
 * multi-group requires either repeatedly tearing down and re-forming groups to "hop" between
 * them, or the newer, more restricted Wi-Fi Aware / Wi-Fi Direct "Group Client" APIs on
 * recent Android versions) and forward packets between groups the same way [BleMeshManager]
 * forwards between BLE neighbours — decrementing `ttl`, de-duplicating by `msgId`. None of
 * that plumbing exists yet; this class only relays within the one group it is currently in.
 */
@SuppressLint("MissingPermission")
class WifiDirectManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private var started = false
    private var discoveryLoopJob: Job? = null
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var clientJob: Job? = null

    /** GO side: one socket per connected client, keyed by its IP (unique per client on the
     * group subnet). Non-GO side: unused, the single upstream socket lives in [upstreamSocket]. */
    private val clients = ConcurrentHashMap<String, Socket>()

    /** Non-GO side: the one socket held open to the group owner. */
    @Volatile private var upstreamSocket: Socket? = null

    private val _status = MutableStateFlow(WifiDirectStatus())
    val status: StateFlow<WifiDirectStatus> = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<WifiDirectPeer>>(emptyList())
    val peers: StateFlow<List<WifiDirectPeer>> = _peers.asStateFlow()

    /** Replay so a screen opened after packets already arrived still sees them, same as
     * the BLE tier's `incoming` flow. */
    private val _incoming = MutableSharedFlow<Packet>(replay = 32, extraBufferCapacity = 64)
    val incoming: SharedFlow<Packet> = _incoming.asSharedFlow()

    fun isSupported(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun isWifiEnabled(): Boolean {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wifiManager?.isWifiEnabled == true
    }

    // ---- lifecycle ----

    fun start() {
        synchronized(lifecycleLock) {
            if (started) {
                WifiDirectLog.i(WifiDirectLog.Step.START, "skipped" to "already-running")
                return
            }
            val blocker = precheck()
            if (blocker != null) {
                WifiDirectLog.w(WifiDirectLog.Step.START, "aborted" to blocker)
                _status.update { it.copy(available = false, lastError = blocker) }
                return
            }

            val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (mgr == null) {
                WifiDirectLog.w(WifiDirectLog.Step.START, "aborted" to "no WifiP2pManager")
                _status.update { it.copy(available = false, lastError = "Wi-Fi Direct is unavailable on this device") }
                return
            }
            manager = mgr
            channel = mgr.initialize(context, context.mainLooper) {
                WifiDirectLog.w(WifiDirectLog.Step.CHANNEL_LOST)
                _status.update { it.copy(available = false, lastError = "Wi-Fi Direct channel lost") }
            }
            registerReceiver()
            started = true
            _status.update { it.copy(available = true, lastError = null) }
            WifiDirectLog.i(WifiDirectLog.Step.START, "sdk" to android.os.Build.VERSION.SDK_INT)

            discoverPeers()
            startDiscoveryLoop()
        }
    }

    private fun precheck(): String? {
        if (!isSupported()) {
            WifiDirectLog.w(WifiDirectLog.Step.PRECHECK, "feature" to "unsupported")
            return "This device has no Wi-Fi Direct hardware"
        }
        if (!isWifiEnabled()) {
            WifiDirectLog.w(WifiDirectLog.Step.PRECHECK, "wifi" to "off")
            return "Wi-Fi is off"
        }
        val missing = WifiDirectPermissions.missingEssential(context)
        if (missing.isNotEmpty()) {
            WifiDirectLog.w(WifiDirectLog.Step.PERMISSION, "missing" to WifiDirectPermissions.describe(missing))
            return "Missing permission: ${WifiDirectPermissions.describe(missing)}"
        }
        if (!WifiDirectPermissions.isLocationServiceOn(context)) {
            WifiDirectLog.w(WifiDirectLog.Step.PRECHECK, "locationService" to "off")
            return "Turn on Location - this Android version needs it to find nearby phones"
        }
        WifiDirectLog.i(WifiDirectLog.Step.PRECHECK, "ok" to true)
        return null
    }

    fun stop() {
        synchronized(lifecycleLock) {
            if (!started) return
            WifiDirectLog.i(WifiDirectLog.Step.STOP)
            started = false
            discoveryLoopJob?.cancel(); discoveryLoopJob = null
            unregisterReceiver()
            teardownSockets()

            val mgr = manager
            val ch = channel
            if (mgr != null && ch != null) {
                runCatching {
                    mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = Unit
                        override fun onFailure(reason: Int) = Unit
                    })
                }
            }
            manager = null
            channel = null
            _status.value = WifiDirectStatus()
            _peers.value = emptyList()
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    // ---- discovery ----

    fun discoverPeers() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                WifiDirectLog.i(WifiDirectLog.Step.DISCOVER_START)
                _status.update { it.copy(discovering = true) }
            }
            override fun onFailure(reason: Int) {
                WifiDirectLog.w(WifiDirectLog.Step.DISCOVER_FAIL, "reason" to reason)
                _status.update { it.copy(discovering = false) }
            }
        })
    }

    /** Peer discovery auto-stops after ~2 minutes on most OEMs; keep re-issuing it while
     * this tier is running and not yet grouped (once grouped there's nothing left to find). */
    private fun startDiscoveryLoop() {
        discoveryLoopJob?.cancel()
        discoveryLoopJob = scope.launch {
            while (isActive) {
                delay(WifiDirectConstants.DISCOVERY_REISSUE_MS)
                if (!isActive || !started) break
                if (!_status.value.groupFormed) {
                    withContext(Dispatchers.Main) { discoverPeers() }
                }
            }
        }
    }

    fun connectToPeer(deviceAddress: String) {
        val mgr = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply { this.deviceAddress = deviceAddress }
        WifiDirectLog.i(WifiDirectLog.Step.CONNECT_REQUEST, "peer" to WifiDirectLog.shortAddr(deviceAddress))
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit // the real outcome arrives via CONNECTION_CHANGED
            override fun onFailure(reason: Int) {
                WifiDirectLog.w(WifiDirectLog.Step.CONNECT_FAIL, "peer" to WifiDirectLog.shortAddr(deviceAddress), "reason" to reason)
                _status.update { it.copy(lastError = "Could not connect to peer") }
            }
        })
    }

    fun disconnectGroup() {
        val mgr = manager ?: return
        val ch = channel ?: return
        runCatching {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        }
    }

    // ---- broadcast receiver ----

    private fun registerReceiver() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        WifiDirectLog.i(WifiDirectLog.Step.PRECHECK, "p2pState" to if (enabled) "ENABLED" else "DISABLED")
                        _status.update { it.copy(available = enabled) }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeersChanged()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged()
                }
            }
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun unregisterReceiver() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun onPeersChanged() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.requestPeers(ch) { peerList ->
            val list = peerList.deviceList.map { device: WifiP2pDevice ->
                WifiDirectPeer(device.deviceName, device.deviceAddress, device.status)
            }
            WifiDirectLog.i(WifiDirectLog.Step.PEERS_CHANGED, "count" to list.size)
            _peers.value = list
        }
    }

    private fun onConnectionChanged() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.requestConnectionInfo(ch) { info: WifiP2pInfo ->
            WifiDirectLog.i(
                WifiDirectLog.Step.CONNECTION_CHANGED,
                "groupFormed" to info.groupFormed,
                "isGroupOwner" to info.isGroupOwner,
            )
            if (info.groupFormed) {
                val goAddress = info.groupOwnerAddress?.hostAddress
                _status.update {
                    it.copy(
                        groupFormed = true,
                        isGroupOwner = info.isGroupOwner,
                        groupOwnerAddress = goAddress,
                        lastError = null,
                    )
                }
                WifiDirectLog.i(WifiDirectLog.Step.GROUP_FORMED, "role" to if (info.isGroupOwner) "OWNER" else "CLIENT", "goAddress" to goAddress)
                if (info.isGroupOwner) {
                    startServer()
                } else if (goAddress != null) {
                    connectToGroupOwnerSocket(goAddress)
                }
            } else {
                WifiDirectLog.i(WifiDirectLog.Step.GROUP_LOST)
                teardownSockets()
                _status.update { it.copy(groupFormed = false, isGroupOwner = false, groupOwnerAddress = null, connectedSockets = 0) }
            }
        }
    }

    // ---- socket layer ----

    private fun startServer() {
        if (serverJob != null) return
        serverJob = scope.launch {
            val server = try {
                ServerSocket(WifiDirectConstants.SOCKET_PORT, WifiDirectConstants.SERVER_BACKLOG)
            } catch (e: IOException) {
                WifiDirectLog.e(WifiDirectLog.Step.SERVER_FAIL, e)
                _status.update { it.copy(lastError = "Could not start the Wi-Fi Direct server: ${e.message}") }
                return@launch
            }
            serverSocket = server
            WifiDirectLog.i(WifiDirectLog.Step.SERVER_START, "port" to WifiDirectConstants.SOCKET_PORT)
            try {
                while (isActive) {
                    val client = try {
                        server.accept()
                    } catch (e: IOException) {
                        if (isActive) WifiDirectLog.w(WifiDirectLog.Step.SERVER_FAIL, "stage" to "accept", "error" to e.message)
                        break
                    }
                    val key = client.inetAddress?.hostAddress ?: client.toString()
                    clients[key] = client
                    _status.update { it.copy(connectedSockets = clients.size) }
                    WifiDirectLog.i(WifiDirectLog.Step.CLIENT_ACCEPTED, "peer" to key, "total" to clients.size)
                    scope.launch { readLoop(client, key) }
                }
            } finally {
                runCatching { server.close() }
                serverSocket = null
            }
        }
    }

    private fun connectToGroupOwnerSocket(hostAddress: String) {
        if (clientJob != null) return
        clientJob = scope.launch {
            while (isActive && _status.value.groupFormed && !_status.value.isGroupOwner) {
                WifiDirectLog.i(WifiDirectLog.Step.CLIENT_CONNECT, "host" to hostAddress)
                val socket = try {
                    Socket().apply {
                        connect(InetSocketAddress(hostAddress, WifiDirectConstants.SOCKET_PORT), WifiDirectConstants.SOCKET_CONNECT_TIMEOUT_MS)
                    }
                } catch (e: IOException) {
                    WifiDirectLog.w(WifiDirectLog.Step.CLIENT_CONNECT_FAIL, "host" to hostAddress, "error" to e.message)
                    delay(WifiDirectConstants.RECONNECT_BACKOFF_MS)
                    continue
                }
                upstreamSocket = socket
                _status.update { it.copy(connectedSockets = 1) }
                WifiDirectLog.i(WifiDirectLog.Step.CLIENT_CONNECT_OK, "host" to hostAddress)
                readLoop(socket, "GO") // suspends until the socket closes
                upstreamSocket = null
                _status.update { it.copy(connectedSockets = 0) }
                if (isActive && _status.value.groupFormed && !_status.value.isGroupOwner) {
                    delay(WifiDirectConstants.RECONNECT_BACKOFF_MS)
                }
            }
        }
    }

    private suspend fun readLoop(socket: Socket, key: String) {
        try {
            val input = socket.getInputStream()
            while (currentCoroutineContext().isActive) {
                val bytes = try {
                    WifiDirectFrame.read(input)
                } catch (e: IOException) {
                    WifiDirectLog.w(WifiDirectLog.Step.RX_BAD, "peer" to key, "error" to e.message)
                    break
                } ?: break // clean EOF
                handleIncomingFrame(bytes, key)
            }
        } finally {
            runCatching { socket.close() }
            WifiDirectLog.i(WifiDirectLog.Step.SOCKET_CLOSED, "peer" to key)
            if (key != "GO") {
                clients.remove(key)
                _status.update { it.copy(connectedSockets = clients.size) }
            }
        }
    }

    private fun handleIncomingFrame(bytes: ByteArray, fromKey: String) {
        val packet = Packet.deserialize(bytes)
        if (packet == null) {
            WifiDirectLog.w(WifiDirectLog.Step.RX_BAD, "from" to fromKey, "bytes" to bytes.size)
            return
        }
        WifiDirectLog.i(
            WifiDirectLog.Step.RX_PACKET,
            "msgId" to WifiDirectLog.shortId(packet.msgId),
            "from" to fromKey,
            "ttl" to packet.ttl,
        )
        scope.launch { _incoming.emit(packet) }

        // Star relay: only the GO can see every client, so it forwards to everyone else in
        // the group (excluding the sender) — the group's one hop. A non-GO client has
        // nobody else to relay to; the GO already did that for it.
        if (_status.value.isGroupOwner) {
            var relayed = 0
            clients.forEach { (key, socket) ->
                if (key == fromKey) return@forEach
                val ok = runCatching { WifiDirectFrame.write(socket.getOutputStream(), bytes) }.isSuccess
                if (ok) relayed++ else runCatching { socket.close() }
            }
            if (relayed > 0) {
                WifiDirectLog.d(WifiDirectLog.Step.RELAY, "msgId" to WifiDirectLog.shortId(packet.msgId), "to" to relayed)
            }
        }
    }

    private fun teardownSockets() {
        serverJob?.cancel(); serverJob = null
        clientJob?.cancel(); clientJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { upstreamSocket?.close() }
        upstreamSocket = null
    }

    // ---- sending ----

    /**
     * Sends [packet] to the rest of the group: every connected client if this device is the
     * GO, or the single upstream socket (which the GO will then relay onward) otherwise.
     * Returns false if there is nobody to send to right now.
     */
    suspend fun send(packet: Packet): Boolean = withContext(Dispatchers.IO) {
        val bytes = packet.serialize()
        if (_status.value.isGroupOwner) {
            val targets = clients.entries.toList()
            if (targets.isEmpty()) {
                WifiDirectLog.w(WifiDirectLog.Step.TX_FAIL, "reason" to "no connected clients")
                return@withContext false
            }
            var anyOk = false
            targets.forEach { (key, socket) ->
                val ok = runCatching { WifiDirectFrame.write(socket.getOutputStream(), bytes) }.isSuccess
                if (ok) anyOk = true else {
                    WifiDirectLog.w(WifiDirectLog.Step.TX_FAIL, "peer" to key)
                    runCatching { socket.close() }
                }
            }
            if (anyOk) WifiDirectLog.i(WifiDirectLog.Step.TX_OK, "msgId" to WifiDirectLog.shortId(packet.msgId), "targets" to targets.size)
            anyOk
        } else {
            val socket = upstreamSocket
            if (socket == null) {
                WifiDirectLog.w(WifiDirectLog.Step.TX_FAIL, "reason" to "not connected to group owner")
                return@withContext false
            }
            val ok = runCatching { WifiDirectFrame.write(socket.getOutputStream(), bytes) }.isSuccess
            if (ok) WifiDirectLog.i(WifiDirectLog.Step.TX_OK, "msgId" to WifiDirectLog.shortId(packet.msgId), "to" to "GO")
            else WifiDirectLog.w(WifiDirectLog.Step.TX_FAIL, "to" to "GO")
            ok
        }
    }
}
