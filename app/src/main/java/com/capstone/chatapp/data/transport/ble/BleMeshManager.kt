package com.capstone.chatapp.data.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Snapshot of what the mesh is actually doing. The individual radio flags matter on a
 * real device: plenty of phones scan happily but cannot advertise at all, and the
 * difference between "running" and "running but undiscoverable" is otherwise invisible.
 */
data class MeshStatus(
    val running: Boolean = false,
    val neighborCount: Int = 0,
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val serverReady: Boolean = false,
    val lastError: String? = null,
)

/**
 * The heart of the BLE mesh. Every device runs BOTH roles:
 *  - Peripheral: advertises the app service + hosts a writable characteristic (GATT server)
 *  - Central: scans for peers and writes packets into their characteristic (GATT client)
 *
 * Broadcast is a store-and-forward flood: an incoming packet is shown once (seen-set
 * de-dup) and re-broadcast to every neighbour except the one it came from, until its
 * TTL runs out or the reach cap is hit. First arrival wins, so the shortest path is the
 * one displayed; later duplicates are dropped.
 *
 * GATT *client* work runs on a bounded pool (up to [BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS]
 * connections at once — Android's stack does not tolerate unbounded overlapping connects),
 * with a per-device lock so two workers never dial the same phone at once. Writes are
 * prioritised over identity reads so an SOS is never stuck behind a Nearby-list lookup,
 * and a failed connect/write is retried with exponential backoff + jitter before the
 * neighbour is given up on.
 *
 * Every stage logs under [BleLog.TAG] — see docs/RUNTIME_TEST.md for the trace a working
 * phone-to-phone SOS produces.
 */
@SuppressLint("MissingPermission")
class BleMeshManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter

    private val lifecycleLock = Any()

    private var gattServer: BluetoothGattServer? = null
    private var workerJob: Job? = null
    private var sweepJob: Job? = null
    private var advertiseRetryJob: Job? = null
    private var scanRetryJob: Job? = null
    private var scanDutyCycleJob: Job? = null
    private var advertiseDutyCycleJob: Job? = null
    private var btStateReceiver: BroadcastReceiver? = null

    /** Caps how many GATT connections the client role runs at once (see
     * [BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS]); jobs beyond the cap simply wait
     * their turn in [writeQueue]/[identityQueue] instead of being dropped. */
    private val connectionSemaphore = Semaphore(BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS)

    /** One lock per device address so two concurrent workers never open overlapping
     * GATT connections to the same phone. */
    private val deviceMutexes = ConcurrentHashMap<String, Mutex>()
    private fun deviceMutex(address: String): Mutex = deviceMutexes.getOrPut(address) { Mutex() }

    /**
     * Replay so a freshly-opened Emergency screen still sees SOSes that arrived while
     * the mesh ran in the foreground service with no UI attached. The UI de-dups by
     * msgId, so replaying is safe.
     */
    private val _incoming = MutableSharedFlow<BlePacket>(replay = 32, extraBufferCapacity = 64)
    val incoming: SharedFlow<BlePacket> = _incoming.asSharedFlow()

    private val _status = MutableStateFlow(MeshStatus())
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    private val _nearbyPeers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val nearbyPeers: StateFlow<List<NearbyPeer>> = _nearbyPeers.asStateFlow()

    /** A discovered phone we can write packets into. */
    private class Neighbor(
        val device: BluetoothDevice,
        @Volatile var lastSeen: Long,
        @Volatile var failures: Int = 0,
    )

    /**
     * A packet still worth pushing to phones we meet in the next [BleConstants.OUTBOX_TTL_MS].
     * Without this an SOS only ever reaches neighbours that were *already* discovered when
     * the button was pressed — the most common reason a two-phone demo "does nothing".
     */
    private class OutboxEntry(
        val packet: BlePacket,
        val createdAt: Long,
        val excludeAddress: String?,
        val delivered: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    // Every one of these is touched from binder threads (scan/GATT callbacks) AND from
    // the coroutine worker, so they must be concurrent — the previous LinkedHashMap/Set
    // versions could throw ConcurrentModificationException in the middle of a flood.
    private val neighbors = ConcurrentHashMap<String, Neighbor>()
    private val nearby = ConcurrentHashMap<String, NearbyPeer>()
    private val identityRequested = ConcurrentHashMap<String, Long>()
    private val lastScanLog = ConcurrentHashMap<String, Long>()
    private val preparedWrites = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val outbox = ConcurrentHashMap<String, OutboxEntry>()

    /** Bounded, TTL-evicted de-dup set (msgId -> first-seen time), guarded by its own lock. */
    private val seen = LinkedHashMap<String, Long>()
    private val seenLock = Any()

    /** In-flight reassembly of a chunked message, keyed by "deviceAddress:groupId". */
    private class ReassemblyEntry(val total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var received = 0
        val startedAt = System.currentTimeMillis()
    }
    private val reassembly = ConcurrentHashMap<String, ReassemblyEntry>()

    private class WriteJob(val device: BluetoothDevice, val msgId: String, val bytes: ByteArray)
    private class IdentityReadJob(val device: BluetoothDevice)

    // Bounded so a very dense room can't grow these without limit; a full queue drops
    // the newest job rather than blocking the caller (see trySendWrite/trySendIdentityRead).
    private val writeQueue = Channel<WriteJob>(BleConstants.JOB_QUEUE_CAPACITY)
    private val identityQueue = Channel<IdentityReadJob>(BleConstants.JOB_QUEUE_CAPACITY)

    @Volatile private var selfId: String = ""
    @Volatile private var selfName: String = ""
    @Volatile private var advertiseAttempts: Int = 0

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    fun isBluetoothSupported(): Boolean = adapter != null

    /** False on phones whose chipset has no peripheral role — they can never be discovered. */
    fun isAdvertisingSupported(): Boolean = adapter?.isMultipleAdvertisementSupported == true

    // ---- lifecycle ----

    /** Start advertising + GATT server + scanning, and the serialized GATT client worker. */
    fun start(selfId: String, selfName: String) {
        synchronized(lifecycleLock) {
            val identityChanged = this.selfId != selfId || this.selfName != selfName
            this.selfId = selfId
            this.selfName = selfName

            if (_status.value.running) {
                BleLog.i(BleLog.Step.START, "skipped" to "already-running", "identityChanged" to identityChanged)
                return
            }

            BleLog.i(
                BleLog.Step.START,
                "selfId" to BleLog.shortId(selfId),
                "selfName" to selfName,
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            )

            val blocker = precheck()
            if (blocker != null) {
                BleLog.w(BleLog.Step.START, "aborted" to blocker)
                _status.update { it.copy(running = false, lastError = blocker) }
                return
            }

            startGattServer()
            startAdvertising()
            startScanning()
            startScanDutyCycleLoop()
            startAdvertiseDutyCycleLoop()
            startWorker()
            startSweeper()
            registerBluetoothStateReceiver()

            val up = gattServer != null || _status.value.scanning
            _status.update { it.copy(running = up, lastError = if (up) null else "Mesh failed to start") }
            BleLog.i(
                BleLog.Step.START,
                "running" to up,
                "server" to (gattServer != null),
                "scanning" to _status.value.scanning,
            )
        }
    }

    /**
     * Reasons the mesh cannot possibly work, checked up front so the failure shows up as
     * one clear log line instead of a silent no-op deep inside the BLE stack.
     */
    private fun precheck(): String? {
        val adapter = adapter
        if (adapter == null) {
            BleLog.w(BleLog.Step.PRECHECK, "bluetooth" to "unsupported")
            return "This device has no Bluetooth adapter"
        }
        if (!adapter.isEnabled) {
            BleLog.w(BleLog.Step.PRECHECK, "bluetooth" to "off")
            return "Bluetooth is off"
        }
        val missing = BlePermissions.missingEssential(context)
        if (missing.isNotEmpty()) {
            BleLog.w(BleLog.Step.PERMISSION, "missing" to BlePermissions.describe(missing))
            return "Missing permission: ${BlePermissions.describe(missing)}"
        }
        if (!BlePermissions.isLocationServiceOn(context)) {
            // Android 11 and below only: scans return nothing at all with the toggle off,
            // and no error is reported anywhere. Worth failing loudly.
            BleLog.w(BleLog.Step.PRECHECK, "locationService" to "off", "reason" to "required below API 31")
            return "Turn on Location - Android ${Build.VERSION.RELEASE} needs it to scan for Bluetooth"
        }
        BleLog.i(BleLog.Step.PRECHECK, "ok" to true, "advertiseSupported" to isAdvertisingSupported())
        return null
    }

    fun stop() {
        synchronized(lifecycleLock) {
            BleLog.i(BleLog.Step.STOP, "neighbors" to neighbors.size)
            unregisterBluetoothStateReceiver()
            teardownRadio()

            workerJob?.cancel(); workerJob = null
            sweepJob?.cancel(); sweepJob = null

            // Drop queued GATT work so a later start() cannot replay stale jobs.
            while (writeQueue.tryReceive().isSuccess) { /* drain */ }
            while (identityQueue.tryReceive().isSuccess) { /* drain */ }

            outbox.clear()
            nearby.clear()
            _nearbyPeers.value = emptyList()
            _status.value = MeshStatus(running = false)
        }
    }

    /** Tear down radio use without touching the BT-state receiver, so we can come back. */
    private fun teardownRadio() {
        advertiseRetryJob?.cancel(); advertiseRetryJob = null
        scanRetryJob?.cancel(); scanRetryJob = null
        scanDutyCycleJob?.cancel(); scanDutyCycleJob = null
        advertiseDutyCycleJob?.cancel(); advertiseDutyCycleJob = null

        if (_status.value.advertising) {
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
                .onFailure { BleLog.w(BleLog.Step.ADV_STOP, "error" to it.message) }
            BleLog.i(BleLog.Step.ADV_STOP)
        }
        if (_status.value.scanning) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
                .onFailure { BleLog.w(BleLog.Step.SCAN_STOP, "error" to it.message) }
            BleLog.i(BleLog.Step.SCAN_STOP)
        }
        if (gattServer != null) {
            runCatching { gattServer?.close() }
                .onFailure { BleLog.w(BleLog.Step.GATT_SERVER_CLOSE, "error" to it.message) }
            BleLog.i(BleLog.Step.GATT_SERVER_CLOSE)
        }
        gattServer = null

        neighbors.clear()
        identityRequested.clear()
        identityFailures.clear()
        lastScanLog.clear()
        preparedWrites.clear()
        reassembly.clear()
        advertiseAttempts = 0
        _status.update {
            it.copy(neighborCount = 0, advertising = false, scanning = false, serverReady = false)
        }
    }

    fun shutdown() {
        stop()
        writeQueue.close()
        identityQueue.close()
        scope.cancel()
    }

    // ---- sending ----

    /**
     * Broadcast a brand-new emergency [packet] originated by this device to every
     * neighbour. The caller builds the packet (so it can reuse the same msgId on the
     * internet path for de-duplication).
     */
    fun send(packet: BlePacket) {
        addSeen(packet.msgId)
        BleLog.i(
            BleLog.Step.SOS_SEND,
            "msgId" to BleLog.shortId(packet.msgId),
            "ttl" to packet.ttl,
            "neighbors" to neighbors.size,
            "chars" to packet.text.length,
        )
        if (neighbors.isEmpty()) {
            // Not an error: the outbox pushes it to the first phone we meet.
            BleLog.w(BleLog.Step.SOS_SEND, "neighbors" to 0, "note" to "held in outbox until a phone is found")
        }
        enqueueToNeighbors(packet, excludeAddress = null)
    }

    /** Convenience factory for a fresh outbound packet from this device. */
    fun newPacket(text: String): BlePacket = BlePacket(
        msgId = UUID.randomUUID().toString(),
        ttl = BleConstants.DEFAULT_TTL,
        hopCount = 0,
        reachCount = 1,
        senderId = selfId,
        senderName = selfName,
        timestamp = System.currentTimeMillis(),
        text = text,
    )

    private fun enqueueToNeighbors(packet: BlePacket, excludeAddress: String?) {
        val entry = outbox.getOrPut(packet.msgId) {
            OutboxEntry(packet, System.currentTimeMillis(), excludeAddress)
        }
        trimOutbox()

        val bytes = packet.toBytes()
        var queued = 0
        neighbors.values.forEach { neighbor ->
            val address = neighbor.device.address
            if (address == excludeAddress) return@forEach
            if (!entry.delivered.add(address)) return@forEach // already sent, or in flight
            if (trySendWrite(WriteJob(neighbor.device, packet.msgId, bytes))) {
                queued++
            } else {
                entry.delivered.remove(address) // let a later opportunity retry
            }
        }
        BleLog.d(
            BleLog.Step.TX_QUEUE,
            "msgId" to BleLog.shortId(packet.msgId),
            "queued" to queued,
            "neighbors" to neighbors.size,
            "excluded" to BleLog.shortAddr(excludeAddress),
        )
    }

    /** Push everything still live in the outbox to a phone we just discovered. */
    private fun flushOutboxTo(neighbor: Neighbor) {
        val now = System.currentTimeMillis()
        val address = neighbor.device.address
        var queued = 0
        outbox.values.forEach { entry ->
            if (now - entry.createdAt > BleConstants.OUTBOX_TTL_MS) return@forEach
            if (address == entry.excludeAddress) return@forEach
            if (!entry.delivered.add(address)) return@forEach
            if (trySendWrite(WriteJob(neighbor.device, entry.packet.msgId, entry.packet.toBytes()))) {
                queued++
            } else {
                entry.delivered.remove(address)
            }
        }
        if (queued > 0) {
            BleLog.i(BleLog.Step.OUTBOX, "to" to BleLog.shortAddr(address), "replayed" to queued)
        }
    }

    private fun trimOutbox() {
        if (outbox.size <= BleConstants.OUTBOX_MAX) return
        outbox.entries
            .sortedBy { it.value.createdAt }
            .take(outbox.size - BleConstants.OUTBOX_MAX)
            .forEach { outbox.remove(it.key) }
    }

    /** Enqueues a write, dropping it politely (logged) if [writeQueue] is already full. */
    private fun trySendWrite(job: WriteJob): Boolean {
        val sent = writeQueue.trySend(job).isSuccess
        if (!sent) {
            BleLog.w(
                BleLog.Step.QUEUE_FULL,
                "queue" to "write",
                "peer" to BleLog.shortAddr(job.device.address),
                "msgId" to BleLog.shortId(job.msgId),
            )
        }
        return sent
    }

    /** Enqueues an identity read, dropping it politely if [identityQueue] is full — the
     * device stays out of [identityRequested] so a later scan result retries it. */
    private fun trySendIdentityRead(job: IdentityReadJob): Boolean {
        val sent = identityQueue.trySend(job).isSuccess
        if (!sent) {
            BleLog.w(BleLog.Step.QUEUE_FULL, "queue" to "identity", "peer" to BleLog.shortAddr(job.device.address))
        }
        return sent
    }

    /** Exponential backoff + jitter for the [attempt]-th retry (1-indexed). */
    private fun retryBackoffMs(attempt: Int): Long {
        val exp = BleConstants.RETRY_BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceAtMost(6))
        val capped = exp.coerceAtMost(BleConstants.RETRY_MAX_BACKOFF_MS)
        return capped + Random.nextLong(0, BleConstants.RETRY_JITTER_MS + 1)
    }

    // ---- GATT server (peripheral / inbound) ----

    private fun startGattServer() {
        val manager = bluetoothManager ?: return
        val server = runCatching { manager.openGattServer(context, serverCallback) }
            .onFailure { BleLog.e(BleLog.Step.GATT_SERVER_FAIL, it, "stage" to "open") }
            .getOrNull()
        if (server == null) {
            BleLog.w(BleLog.Step.GATT_SERVER_FAIL, "reason" to "openGattServer returned null")
            _status.update { it.copy(serverReady = false, lastError = "Could not open the Bluetooth GATT server") }
            return
        }
        BleLog.i(BleLog.Step.GATT_SERVER_OPEN, "service" to BleConstants.SERVICE_UUID)

        val message = BluetoothGattCharacteristic(
            BleConstants.MESSAGE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        // Readable identity characteristic so peers can label us in their Nearby list.
        val identity = BluetoothGattCharacteristic(
            BleConstants.IDENTITY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(message)
        service.addCharacteristic(identity)

        gattServer = server
        // addService is asynchronous - readiness is confirmed in onServiceAdded below.
        if (!server.addService(service)) {
            BleLog.w(BleLog.Step.GATT_SERVER_FAIL, "reason" to "addService rejected")
        }
    }

    /** "uid|name" this device reports to peers reading our identity characteristic. */
    private fun identityBytes(): ByteArray =
        "$selfId|${selfName.replace('|', '/')}".toByteArray(Charsets.UTF_8)

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            if (ok) {
                BleLog.i(BleLog.Step.GATT_SERVER_READY, "service" to service.uuid)
            } else {
                BleLog.w(BleLog.Step.GATT_SERVER_FAIL, "stage" to "addService", "status" to BleLog.gattStatus(status))
            }
            _status.update { it.copy(serverReady = ok) }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            BleLog.d(
                BleLog.Step.GATT_SERVER_CONN,
                "peer" to BleLog.shortAddr(device.address),
                "state" to if (newState == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else "DISCONNECTED",
                "status" to BleLog.gattStatus(status),
            )
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                preparedWrites.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            BleLog.d(BleLog.Step.TX_MTU, "side" to "server", "peer" to BleLog.shortAddr(device.address), "mtu" to mtu)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == BleConstants.IDENTITY_CHARACTERISTIC_UUID) {
                val bytes = identityBytes()
                val value = if (offset >= bytes.size) ByteArray(0) else bytes.copyOfRange(offset, bytes.size)
                BleLog.i(
                    BleLog.Step.IDENTITY_SERVED,
                    "peer" to BleLog.shortAddr(device.address),
                    "offset" to offset,
                    "bytes" to value.size,
                )
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid != BleConstants.MESSAGE_CHARACTERISTIC_UUID) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                }
                return
            }

            if (preparedWrite) {
                // Long write: Android splits anything larger than (MTU - 3) into chunks
                // and only commits them on onExecuteWrite. Without this branch every
                // message that overflowed the negotiated MTU was silently dropped.
                val buffer = preparedWrites.getOrPut(device.address) { ByteArrayOutputStream() }
                buffer.write(value)
                BleLog.d(
                    BleLog.Step.RX_PACKET,
                    "peer" to BleLog.shortAddr(device.address),
                    "prepared" to true,
                    "chunk" to value.size,
                    "buffered" to buffer.size(),
                )
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
                return
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            handleChunkWrite(value, device.address)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buffer = preparedWrites.remove(device.address)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (!execute || buffer == null) return
            handleChunkWrite(buffer.toByteArray(), device.address)
        }
    }

    /**
     * Parses one [BleChunk] envelope and, once every chunk in its group has arrived,
     * reassembles and hands the full packet bytes to [acceptBytes]. A message that fit in
     * a single chunk (the common case) is delivered immediately.
     */
    private fun handleChunkWrite(value: ByteArray, address: String?) {
        if (address == null) return
        val chunk = BleChunk.parse(value)
        if (chunk == null) {
            BleLog.w(BleLog.Step.RX_BAD, "peer" to BleLog.shortAddr(address), "bytes" to value.size, "reason" to "bad chunk")
            return
        }
        if (chunk.total == 1) {
            acceptBytes(chunk.payload, address)
            return
        }
        val key = "$address:${chunk.groupId}"
        val entry = reassembly.getOrPut(key) { ReassemblyEntry(chunk.total) }
        val complete = synchronized(entry) {
            if (chunk.seq in entry.parts.indices && entry.parts[chunk.seq] == null) {
                entry.parts[chunk.seq] = chunk.payload
                entry.received++
            }
            entry.received >= entry.total
        }
        BleLog.d(
            BleLog.Step.RX_PACKET,
            "peer" to BleLog.shortAddr(address),
            "chunk" to "${chunk.seq + 1}/${chunk.total}",
            "groupId" to chunk.groupId,
        )
        if (complete) {
            reassembly.remove(key)
            val combined = ByteArrayOutputStream().apply {
                entry.parts.forEach { part -> part?.let { write(it) } }
            }.toByteArray()
            acceptBytes(combined, address)
        }
    }

    private fun acceptBytes(bytes: ByteArray, fromAddress: String?) {
        val packet = BlePacket.fromBytes(bytes)
        if (packet == null) {
            BleLog.w(BleLog.Step.RX_BAD, "peer" to BleLog.shortAddr(fromAddress), "bytes" to bytes.size)
            return
        }
        handleIncoming(packet, fromAddress)
    }

    private fun handleIncoming(packet: BlePacket, fromAddress: String?) {
        BleLog.i(
            BleLog.Step.RX_PACKET,
            "msgId" to BleLog.shortId(packet.msgId),
            "from" to BleLog.shortAddr(fromAddress),
            "sender" to packet.senderName,
            "hops" to packet.hopCount,
            "ttl" to packet.ttl,
            "reach" to packet.reachCount,
            "chars" to packet.text.length,
        )

        if (!addSeen(packet.msgId)) {
            // A duplicate means a shorter path already delivered this one.
            BleLog.i(
                BleLog.Step.DEDUP_DROP,
                "msgId" to BleLog.shortId(packet.msgId),
                "from" to BleLog.shortAddr(fromAddress),
                "hops" to packet.hopCount,
            )
            return
        }

        scope.launch { _incoming.emit(packet) }

        when {
            packet.ttl <= 1 -> BleLog.i(
                BleLog.Step.TTL_DROP,
                "msgId" to BleLog.shortId(packet.msgId),
                "reason" to "ttl exhausted",
                "ttl" to packet.ttl,
            )
            packet.reachCount >= BleConstants.REACH_CAP -> BleLog.i(
                BleLog.Step.TTL_DROP,
                "msgId" to BleLog.shortId(packet.msgId),
                "reason" to "reach cap",
                "reach" to packet.reachCount,
            )
            else -> {
                val relayed = packet.relayed()
                // A randomized delay before re-broadcasting: every phone in radio range
                // just received the same packet and would otherwise all hit the same
                // neighbours' GATT servers in the same instant.
                val jitter = Random.nextLong(0, BleConstants.REBROADCAST_JITTER_MAX_MS + 1)
                BleLog.i(
                    BleLog.Step.RELAY_SEND,
                    "msgId" to BleLog.shortId(relayed.msgId),
                    "ttl" to "${packet.ttl}->${relayed.ttl}",
                    "hops" to "${packet.hopCount}->${relayed.hopCount}",
                    "exclude" to BleLog.shortAddr(fromAddress),
                    "jitterMs" to jitter,
                )
                scope.launch {
                    delay(jitter)
                    enqueueToNeighbors(relayed, excludeAddress = fromAddress)
                }
            }
        }
    }

    // ---- Advertising ----

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            // No peripheral role: this phone can still send, but nobody will ever
            // discover it, so nobody can deliver an SOS to it. Worth shouting about.
            BleLog.w(
                BleLog.Step.ADV_FAIL,
                "reason" to "no LE advertiser",
                "note" to "this phone cannot be discovered by others",
            )
            _status.update { it.copy(advertising = false, lastError = "This phone cannot advertise over Bluetooth LE") }
            return
        }
        advertiseAttempts++
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // The device name is omitted on purpose: a 128-bit service UUID already eats 18
        // of the 31 advertisement bytes, and a long name tips it into DATA_TOO_LARGE.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        BleLog.i(BleLog.Step.ADV_START, "attempt" to advertiseAttempts, "mode" to "LOW_LATENCY", "connectable" to true)
        runCatching { advertiser.startAdvertising(settings, data, advertiseCallback) }
            .onFailure { BleLog.e(BleLog.Step.ADV_FAIL, it, "stage" to "startAdvertising") }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            BleLog.i(
                BleLog.Step.ADV_OK,
                "txPower" to settingsInEffect?.txPowerLevel,
                "mode" to settingsInEffect?.mode,
            )
            advertiseAttempts = 0
            _status.update { it.copy(advertising = true) }
        }

        override fun onStartFailure(errorCode: Int) {
            val reason = BleLog.advertiseError(errorCode)
            BleLog.w(BleLog.Step.ADV_FAIL, "error" to reason, "attempt" to advertiseAttempts)
            _status.update { it.copy(advertising = false, lastError = "Advertising failed: $reason") }

            when (errorCode) {
                // Already advertising is fine - treat it as success rather than looping.
                ADVERTISE_FAILED_ALREADY_STARTED ->
                    _status.update { it.copy(advertising = true, lastError = null) }
                // The hardware cannot do it; retrying forever would just burn battery.
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED, ADVERTISE_FAILED_DATA_TOO_LARGE -> Unit
                else -> scheduleAdvertiseRestart()
            }
        }
    }

    /** Recoverable advertise failures (internal error, too many advertisers) get a few retries. */
    private fun scheduleAdvertiseRestart() {
        if (advertiseAttempts >= 4) {
            BleLog.w(BleLog.Step.ADV_RESTART, "givingUp" to true, "attempts" to advertiseAttempts)
            return
        }
        advertiseRetryJob?.cancel()
        advertiseRetryJob = scope.launch {
            delay(BleConstants.RESTART_BACKOFF_MS * advertiseAttempts)
            if (!_status.value.running || !isBluetoothOn()) return@launch
            BleLog.i(BleLog.Step.ADV_RESTART, "attempt" to advertiseAttempts + 1)
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
            startAdvertising()
        }
    }

    // ---- Scanning (central / neighbour discovery) ----

    private fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            BleLog.w(BleLog.Step.SCAN_FAIL, "reason" to "no LE scanner")
            _status.update { it.copy(scanning = false, lastError = "Bluetooth LE scanning is unavailable") }
            return
        }
        // The service-UUID filter is not just an optimisation: an unfiltered scan is
        // dropped entirely while the screen is off.
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                }
            }
            .build()
        BleLog.i(BleLog.Step.SCAN_START, "filter" to BleConstants.SERVICE_UUID, "mode" to "LOW_LATENCY")
        val started = runCatching { scanner.startScan(filters, settings, scanCallback) }
            .onFailure { BleLog.e(BleLog.Step.SCAN_FAIL, it, "stage" to "startScan") }
            .isSuccess
        _status.update { it.copy(scanning = started) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            onDeviceSeen(device, result.rssi)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { result -> result.device?.let { onDeviceSeen(it, result.rssi) } }
        }

        override fun onScanFailed(errorCode: Int) {
            val reason = BleLog.scanError(errorCode)
            BleLog.w(BleLog.Step.SCAN_FAIL, "error" to reason)
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> _status.update { it.copy(scanning = true) }
                SCAN_FAILED_FEATURE_UNSUPPORTED -> _status.update {
                    it.copy(scanning = false, lastError = "Bluetooth LE scanning is unsupported")
                }
                else -> {
                    _status.update { it.copy(scanning = false, lastError = "Scan failed: $reason") }
                    // Error 6 is Android throttling us (5 scan starts per 30s). It clears
                    // on its own, so back off past the window rather than hammering.
                    scheduleScanRestart(if (errorCode == 6) 35_000 else BleConstants.RESTART_BACKOFF_MS)
                }
            }
        }
    }

    private fun scheduleScanRestart(delayMs: Long) {
        scanRetryJob?.cancel()
        scanRetryJob = scope.launch {
            delay(delayMs)
            if (!_status.value.running || !isBluetoothOn()) return@launch
            BleLog.i(BleLog.Step.SCAN_START, "restart" to true, "afterMs" to delayMs)
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
            startScanning()
        }
    }

    /**
     * Alternates the scanner between ON and OFF windows so it doesn't run
     * SCAN_MODE_LOW_LATENCY continuously. Off by [BleConstants.SCAN_DUTY_CYCLE_ENABLED],
     * windows sized by [BleConstants.SCAN_WINDOW_ON_MS]/[BleConstants.SCAN_WINDOW_OFF_MS].
     */
    private fun startScanDutyCycleLoop() {
        if (!BleConstants.SCAN_DUTY_CYCLE_ENABLED) return
        scanDutyCycleJob?.cancel()
        scanDutyCycleJob = scope.launch {
            while (isActive) {
                delay(BleConstants.SCAN_WINDOW_ON_MS)
                if (!isActive || !_status.value.running) break
                BleLog.d(BleLog.Step.DUTY_CYCLE, "radio" to "scan", "window" to "OFF")
                runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
                _status.update { it.copy(scanning = false) }
                delay(BleConstants.SCAN_WINDOW_OFF_MS)
                if (!isActive || !_status.value.running || !isBluetoothOn()) break
                BleLog.d(BleLog.Step.DUTY_CYCLE, "radio" to "scan", "window" to "ON")
                startScanning()
            }
        }
    }

    /** Same idea as [startScanDutyCycleLoop] but for advertising; off by default since an
     * OFF window makes this phone briefly undiscoverable (see [BleConstants.ADVERTISE_DUTY_CYCLE_ENABLED]). */
    private fun startAdvertiseDutyCycleLoop() {
        if (!BleConstants.ADVERTISE_DUTY_CYCLE_ENABLED) return
        advertiseDutyCycleJob?.cancel()
        advertiseDutyCycleJob = scope.launch {
            while (isActive) {
                delay(BleConstants.ADVERTISE_WINDOW_ON_MS)
                if (!isActive || !_status.value.running) break
                BleLog.d(BleLog.Step.DUTY_CYCLE, "radio" to "advertise", "window" to "OFF")
                runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
                _status.update { it.copy(advertising = false) }
                delay(BleConstants.ADVERTISE_WINDOW_OFF_MS)
                if (!isActive || !_status.value.running || !isBluetoothOn()) break
                BleLog.d(BleLog.Step.DUTY_CYCLE, "radio" to "advertise", "window" to "ON")
                startAdvertising()
            }
        }
    }

    private fun onDeviceSeen(device: BluetoothDevice, rssi: Int) {
        val now = System.currentTimeMillis()
        val address = device.address ?: return

        val existing = neighbors[address]
        if (existing == null) {
            val neighbor = Neighbor(device, now)
            neighbors[address] = neighbor
            _status.update { it.copy(neighborCount = neighbors.size) }
            BleLog.i(
                BleLog.Step.NEIGHBOR_ADD,
                "peer" to BleLog.shortAddr(address),
                "rssi" to rssi,
                "total" to neighbors.size,
            )
            // Anything still worth broadcasting goes out to this phone right away.
            flushOutboxTo(neighbor)
        } else {
            existing.lastSeen = now
            existing.failures = 0
        }

        // Advertisements arrive several times a second; keep the log readable.
        val lastLogged = lastScanLog[address] ?: 0L
        if (now - lastLogged > 10_000) {
            lastScanLog[address] = now
            BleLog.d(BleLog.Step.SCAN_RESULT, "peer" to BleLog.shortAddr(address), "rssi" to rssi)
        }

        // Read identity once per device; the sweeper makes failures eligible for a retry.
        if (identityRequested.putIfAbsent(address, now) == null) {
            BleLog.i(BleLog.Step.IDENTITY_REQ, "peer" to BleLog.shortAddr(address))
            if (!trySendIdentityRead(IdentityReadJob(device))) {
                identityRequested.remove(address) // let the next scan result retry
            }
        }
    }

    // ---- GATT client worker pool (bounded concurrency) ----

    /** Failed identity reads, counted separately from [Neighbor.failures] (write failures)
     * so a flaky identity read can't get a perfectly-writable neighbour dropped. */
    private val identityFailures = ConcurrentHashMap<String, Int>()

    /**
     * Dispatches queued jobs onto a bounded pool of concurrent GATT connections (see
     * [connectionSemaphore], sized by [BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS]).
     * Writes still win over identity reads — checked first every loop iteration — so an
     * SOS is never stuck behind a Nearby-list lookup; what changed from the old
     * fully-serial worker is that up to N distinct devices can now be mid-connection at
     * once instead of one at a time, while [deviceMutex] still stops two workers from
     * opening overlapping connections to the *same* device.
     */
    private fun startWorker() {
        workerJob?.cancel()
        workerJob = scope.launch {
            try {
                while (isActive) {
                    val write = writeQueue.tryReceive().getOrNull()
                    if (write != null) { dispatch { runWrite(write) }; continue }

                    val read = identityQueue.tryReceive().getOrNull()
                    if (read != null) { dispatch { runIdentityRead(read) }; continue }

                    select {
                        writeQueue.onReceive { dispatch { runWrite(it) } }
                        identityQueue.onReceive { dispatch { runIdentityRead(it) } }
                    }
                }
            } catch (_: ClosedReceiveChannelException) {
                // shutdown() closed the queues.
            }
        }
    }

    /** Runs [block] on its own coroutine gated by [connectionSemaphore], so at most
     * [BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS] client connections are ever open. */
    private fun dispatch(block: suspend () -> Unit) {
        scope.launch { connectionSemaphore.withPermit { block() } }
    }

    private suspend fun runWrite(job: WriteJob) {
        val ok = sendToDevice(job.device, job.msgId, job.bytes)
        val neighbor = neighbors[job.device.address]
        if (ok) {
            neighbor?.failures = 0
        } else if (neighbor != null) {
            neighbor.failures++
            // Clear the delivery mark so the outbox can retry if we meet this phone again.
            outbox[job.msgId]?.delivered?.remove(job.device.address)
            if (neighbor.failures >= BleConstants.NEIGHBOR_FAILURE_LIMIT) {
                dropNeighbor(job.device.address, "write failures")
            } else {
                // Reconnect/retry: back off (exponential + jitter) instead of hammering a
                // neighbour that's momentarily out of range or mid-connection elsewhere.
                scheduleRetry(neighbor.failures) { trySendWrite(job) }
            }
        }
        // The stack needs a breath between connects; back-to-back ones yield status 133.
        delay(200)
    }

    private suspend fun runIdentityRead(job: IdentityReadJob) {
        val address = job.device.address
        val ok = readIdentity(job.device)
        if (ok) {
            identityFailures.remove(address)
        } else {
            val attempts = identityFailures.merge(address, 1, Int::plus) ?: 1
            if (attempts < BleConstants.NEIGHBOR_FAILURE_LIMIT) {
                scheduleRetry(attempts) { trySendIdentityRead(job) }
            } else {
                identityFailures.remove(address)
                identityRequested.remove(address) // eligible again on the next scan result
            }
        }
        delay(200)
    }

    /** Schedules [enqueue] after an exponential-backoff-plus-jitter delay (see
     * [retryBackoffMs]) instead of retrying inline, so a flaky neighbour can't tie up a
     * worker slot spinning. */
    private fun scheduleRetry(attempt: Int, enqueue: () -> Boolean) {
        val delayMs = retryBackoffMs(attempt)
        BleLog.i(BleLog.Step.RETRY_SCHEDULE, "attempt" to attempt, "inMs" to delayMs)
        scope.launch {
            delay(delayMs)
            enqueue()
        }
    }

    private fun dropNeighbor(address: String, reason: String) {
        if (neighbors.remove(address) != null) {
            identityRequested.remove(address)
            identityFailures.remove(address)
            lastScanLog.remove(address)
            _status.update { it.copy(neighborCount = neighbors.size) }
            BleLog.i(
                BleLog.Step.NEIGHBOR_DROP,
                "peer" to BleLog.shortAddr(address),
                "reason" to reason,
                "total" to neighbors.size,
            )
        }
    }

    /**
     * Connect to [device] and read its identity characteristic ("uid|name"), adding it to
     * the Nearby list. [deviceMutex] guarantees this never overlaps a write (or another
     * identity read) to the same device, even though multiple *other* devices may now be
     * connecting concurrently. Returns false on a failure worth retrying.
     */
    private suspend fun readIdentity(device: BluetoothDevice): Boolean = deviceMutex(device.address).withLock {
        val identity = withTimeoutOrNull(BleConstants.GATT_OP_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var settled = false
                fun finish(result: String?, gatt: BluetoothGatt?) {
                    if (settled) return
                    settled = true
                    runCatching { gatt?.disconnect(); gatt?.close() }
                    if (cont.isActive) cont.resume(result)
                }
                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            BleLog.w(
                                BleLog.Step.IDENTITY_FAIL,
                                "peer" to BleLog.shortAddr(device.address),
                                "stage" to "connect",
                                "status" to BleLog.gattStatus(status),
                            )
                            finish(null, gatt)
                            return
                        }
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> if (!gatt.discoverServices()) finish(null, gatt)
                            BluetoothProfile.STATE_DISCONNECTED -> finish(null, gatt)
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) { finish(null, gatt); return }
                        val characteristic = gatt.getService(BleConstants.SERVICE_UUID)
                            ?.getCharacteristic(BleConstants.IDENTITY_CHARACTERISTIC_UUID)
                        if (characteristic == null) {
                            BleLog.w(
                                BleLog.Step.IDENTITY_FAIL,
                                "peer" to BleLog.shortAddr(device.address),
                                "stage" to "discover",
                                "reason" to "identity characteristic missing",
                            )
                            finish(null, gatt)
                            return
                        }
                        if (!gatt.readCharacteristic(characteristic)) finish(null, gatt)
                    }

                    @Deprecated("Only API 32 and below call this overload")
                    @Suppress("DEPRECATION")
                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        val value = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
                        finish(value?.toString(Charsets.UTF_8), gatt)
                    }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                        status: Int,
                    ) {
                        val text = if (status == BluetoothGatt.GATT_SUCCESS) value.toString(Charsets.UTF_8) else null
                        finish(text, gatt)
                    }
                }
                val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    BleLog.w(
                        BleLog.Step.IDENTITY_FAIL,
                        "peer" to BleLog.shortAddr(device.address),
                        "stage" to "connectGatt",
                        "reason" to "null gatt",
                    )
                    finish(null, null)
                } else {
                    cont.invokeOnCancellation { runCatching { gatt.disconnect(); gatt.close() } }
                }
            }
        }

        if (identity == null) {
            BleLog.w(BleLog.Step.IDENTITY_FAIL, "peer" to BleLog.shortAddr(device.address), "reason" to "no value")
            return@withLock false
        }
        val parts = identity.split("|", limit = 2)
        val uid = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        if (uid == null) {
            BleLog.w(BleLog.Step.IDENTITY_FAIL, "peer" to BleLog.shortAddr(device.address), "reason" to "blank uid")
            return@withLock false
        }
        val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Someone"
        if (uid == selfId) {
            BleLog.d(BleLog.Step.IDENTITY_OK, "peer" to BleLog.shortAddr(device.address), "self" to true)
            return@withLock true
        }
        BleLog.i(
            BleLog.Step.IDENTITY_OK,
            "peer" to BleLog.shortAddr(device.address),
            "uid" to BleLog.shortId(uid),
            "name" to name,
        )
        nearby[uid] = NearbyPeer(uid, name, device.address, System.currentTimeMillis())
        publishNearby()
        true
    }

    private fun publishNearby() {
        _nearbyPeers.value = nearby.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Connects to [device] and writes [bytes] as one or more [BleChunk] envelopes (see
     * [BleChunk] for why — the negotiated MTU may be far smaller than the payload).
     * [deviceMutex] guarantees this never overlaps another connection to the same device.
     */
    private suspend fun sendToDevice(device: BluetoothDevice, msgId: String, bytes: ByteArray): Boolean =
        deviceMutex(device.address).withLock {
        val peer = BleLog.shortAddr(device.address)
        val groupId = Random.nextInt()
        BleLog.i(BleLog.Step.TX_CONNECT, "peer" to peer, "msgId" to BleLog.shortId(msgId), "bytes" to bytes.size)

        val ok = withTimeoutOrNull(BleConstants.GATT_OP_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var settled = false
                var chunks: List<ByteArray> = emptyList()
                var chunkIndex = 0
                fun finish(result: Boolean, gatt: BluetoothGatt?) {
                    if (settled) return
                    settled = true
                    runCatching { gatt?.disconnect(); gatt?.close() }
                    if (cont.isActive) cont.resume(result)
                }
                fun buildChunks(mtu: Int): List<ByteArray> {
                    val payloadSize = (mtu - 3 - BleConstants.CHUNK_HEADER_SIZE)
                        .coerceAtLeast(BleConstants.CHUNK_MIN_PAYLOAD)
                    return BleChunk.split(bytes, payloadSize, groupId)
                }
                fun sendChunk(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
                    BleLog.d(
                        BleLog.Step.TX_WRITE,
                        "peer" to peer,
                        "msgId" to BleLog.shortId(msgId),
                        "chunk" to "${chunkIndex + 1}/${chunks.size}",
                    )
                    return writeToCharacteristic(gatt, characteristic, chunks[chunkIndex])
                }
                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            BleLog.w(
                                BleLog.Step.TX_FAIL,
                                "peer" to peer,
                                "msgId" to BleLog.shortId(msgId),
                                "stage" to "connect",
                                "status" to BleLog.gattStatus(status),
                            )
                            finish(false, gatt)
                            return
                        }
                        when (newState) {
                            // If the MTU request is rejected outright, fall straight
                            // through to discovery (with the default 23-byte ATT MTU)
                            // instead of waiting for the timeout.
                            BluetoothProfile.STATE_CONNECTED ->
                                if (!gatt.requestMtu(BleConstants.REQUESTED_MTU)) {
                                    chunks = buildChunks(23)
                                    gatt.discoverServices()
                                }
                            BluetoothProfile.STATE_DISCONNECTED -> finish(false, gatt)
                        }
                    }

                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        val negotiated = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
                        chunks = buildChunks(negotiated)
                        BleLog.d(
                            BleLog.Step.TX_MTU,
                            "side" to "client",
                            "peer" to peer,
                            "mtu" to negotiated,
                            "status" to BleLog.gattStatus(status),
                            "payload" to bytes.size,
                            "chunks" to chunks.size,
                        )
                        if (!gatt.discoverServices()) finish(false, gatt)
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            BleLog.w(
                                BleLog.Step.TX_FAIL,
                                "peer" to peer,
                                "stage" to "discover",
                                "status" to BleLog.gattStatus(status),
                            )
                            finish(false, gatt)
                            return
                        }
                        val characteristic = gatt.getService(BleConstants.SERVICE_UUID)
                            ?.getCharacteristic(BleConstants.MESSAGE_CHARACTERISTIC_UUID)
                        if (characteristic == null) {
                            BleLog.w(
                                BleLog.Step.TX_FAIL,
                                "peer" to peer,
                                "stage" to "discover",
                                "reason" to "message characteristic missing",
                            )
                            finish(false, gatt)
                            return
                        }
                        chunkIndex = 0
                        if (chunks.isEmpty()) chunks = buildChunks(23) // onMtuChanged never fired
                        if (!sendChunk(gatt, characteristic)) {
                            BleLog.w(BleLog.Step.TX_FAIL, "peer" to peer, "stage" to "write", "reason" to "rejected")
                            finish(false, gatt)
                        }
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            BleLog.w(
                                BleLog.Step.TX_FAIL,
                                "peer" to peer,
                                "msgId" to BleLog.shortId(msgId),
                                "stage" to "write",
                                "chunk" to "${chunkIndex + 1}/${chunks.size}",
                                "status" to BleLog.gattStatus(status),
                            )
                            finish(false, gatt)
                            return
                        }
                        chunkIndex++
                        if (chunkIndex >= chunks.size) {
                            BleLog.i(
                                BleLog.Step.TX_OK,
                                "peer" to peer,
                                "msgId" to BleLog.shortId(msgId),
                                "chunks" to chunks.size,
                            )
                            finish(true, gatt)
                            return
                        }
                        if (!sendChunk(gatt, characteristic)) {
                            BleLog.w(
                                BleLog.Step.TX_FAIL,
                                "peer" to peer,
                                "stage" to "write",
                                "reason" to "rejected",
                                "chunk" to "${chunkIndex + 1}/${chunks.size}",
                            )
                            finish(false, gatt)
                        }
                    }
                }
                val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    BleLog.w(BleLog.Step.TX_FAIL, "peer" to peer, "stage" to "connectGatt", "reason" to "null gatt")
                    finish(false, null)
                } else {
                    cont.invokeOnCancellation { runCatching { gatt.disconnect(); gatt.close() } }
                }
            }
        }

        if (ok == null) {
            BleLog.w(
                BleLog.Step.TX_FAIL,
                "peer" to peer,
                "msgId" to BleLog.shortId(msgId),
                "stage" to "timeout",
                "afterMs" to BleConstants.GATT_OP_TIMEOUT_MS,
            )
        }
        ok ?: false
    }

    /** Returns false when the platform rejects the write outright, so we skip the wait. */
    @Suppress("DEPRECATION")
    private fun writeToCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(
            characteristic,
            bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ) == BluetoothGatt.GATT_SUCCESS
    } else {
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = bytes
        gatt.writeCharacteristic(characteristic)
    }

    // ---- Bluetooth adapter state ----

    /**
     * Without this, toggling Bluetooth off and on left the mesh in a zombie state:
     * `running` still true, but no advertiser, no scanner and no GATT server.
     */
    private fun registerBluetoothStateReceiver() {
        if (btStateReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> synchronized(lifecycleLock) {
                        if (!_status.value.running) return@synchronized
                        BleLog.i(BleLog.Step.BT_STATE, "state" to "ON", "action" to "restarting radio")
                        teardownRadio()
                        startGattServer()
                        startAdvertising()
                        startScanning()
                        startScanDutyCycleLoop()
                        startAdvertiseDutyCycleLoop()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> synchronized(lifecycleLock) {
                        BleLog.w(BleLog.Step.BT_STATE, "state" to "OFF", "action" to "radio down")
                        teardownRadio()
                        _status.update { it.copy(lastError = "Bluetooth was turned off") }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        btStateReceiver = receiver
    }

    private fun unregisterBluetoothStateReceiver() {
        btStateReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        btStateReceiver = null
    }

    // ---- housekeeping ----

    /**
     * Evicts phones that walked away. Left alone they stay in [neighbors] forever, and
     * every broadcast then burns a full GATT timeout per ghost - which, because writes
     * are serialized, turns into minutes of delay for a real SOS.
     */
    private fun startSweeper() {
        sweepJob?.cancel()
        sweepJob = scope.launch {
            while (isActive) {
                delay(BleConstants.SWEEP_INTERVAL_MS)
                val now = System.currentTimeMillis()

                neighbors.values
                    .filter { now - it.lastSeen > BleConstants.NEIGHBOR_STALE_MS }
                    .forEach { dropNeighbor(it.device.address, "stale ${(now - it.lastSeen) / 1000}s") }

                val stalePeers = nearby.filterValues { now - it.lastSeen > BleConstants.PEER_STALE_MS }.keys
                if (stalePeers.isNotEmpty()) {
                    stalePeers.forEach { nearby.remove(it) }
                    publishNearby()
                }

                outbox.filterValues { now - it.createdAt > BleConstants.OUTBOX_TTL_MS }.keys
                    .forEach { outbox.remove(it) }

                // Devices whose identity read failed become eligible for another try.
                identityRequested.entries
                    .filter { now - it.value > BleConstants.PEER_STALE_MS }
                    .forEach { identityRequested.remove(it.key) }

                // A multi-chunk message whose sender vanished (or dropped a chunk)
                // mid-transfer would otherwise sit in memory forever.
                reassembly.entries
                    .filter { now - it.value.startedAt > BleConstants.REASSEMBLY_TTL_MS }
                    .forEach { (key, entry) ->
                        reassembly.remove(key)
                        BleLog.w(
                            BleLog.Step.REASSEMBLY_DROP,
                            "key" to key,
                            "received" to entry.received,
                            "total" to entry.total,
                        )
                    }

                evictExpiredSeen(now)
            }
        }
    }

    private fun addSeen(msgId: String): Boolean = synchronized(seenLock) {
        if (seen.containsKey(msgId)) return@synchronized false
        seen[msgId] = System.currentTimeMillis()
        if (seen.size > BleConstants.SEEN_MAX) {
            val iter = seen.entries.iterator()
            repeat(seen.size - BleConstants.SEEN_MAX) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        true
    }

    /** Evicts seen-set entries older than [BleConstants.SEEN_TTL_MS], independent of the
     * size-based trim in [addSeen] — a quiet mesh should still free old ids over time. */
    private fun evictExpiredSeen(now: Long) = synchronized(seenLock) {
        seen.entries.removeAll { now - it.value > BleConstants.SEEN_TTL_MS }
    }
}
