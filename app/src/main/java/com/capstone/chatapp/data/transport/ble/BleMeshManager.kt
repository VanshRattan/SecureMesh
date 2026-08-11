package com.capstone.chatapp.data.transport.ble

import android.annotation.SuppressLint
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
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

data class MeshStatus(val running: Boolean = false, val neighborCount: Int = 0)

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
 * Outbound writes are processed one-at-a-time via [outbound] to avoid overlapping GATT
 * operations (Android's stack does not like concurrent connects/writes).
 */
@SuppressLint("MissingPermission")
class BleMeshManager(private val context: Context) {

    private val tag = "BleMesh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertising = false
    private var scanning = false

    private val _incoming = MutableSharedFlow<BlePacket>(extraBufferCapacity = 64)
    val incoming: SharedFlow<BlePacket> = _incoming.asSharedFlow()

    private val _status = MutableStateFlow(MeshStatus())
    val status: StateFlow<MeshStatus> = _status.asStateFlow()

    // Identified people nearby (uid+name read over GATT), keyed by uid.
    private val _nearbyPeers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val nearbyPeers: StateFlow<List<NearbyPeer>> = _nearbyPeers.asStateFlow()
    private val nearby = LinkedHashMap<String, NearbyPeer>()

    // De-dup of message ids we've already handled (bounded).
    private val seen = LinkedHashSet<String>()
    // Currently discovered neighbours, keyed by address.
    private val neighbors = LinkedHashMap<String, BluetoothDevice>()
    // Addresses we've already asked for identity, so we don't re-read on every scan hit.
    private val identityRequested = LinkedHashSet<String>()

    private sealed interface GattJob { val device: BluetoothDevice }
    private data class WriteJob(override val device: BluetoothDevice, val bytes: ByteArray) : GattJob
    private data class IdentityReadJob(override val device: BluetoothDevice) : GattJob
    private val outbound = Channel<GattJob>(Channel.UNLIMITED)

    private var selfId: String = ""
    private var selfName: String = ""

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /** Start advertising + GATT server + scanning, and the outbound write worker. */
    fun start(selfId: String, selfName: String) {
        if (_status.value.running) return
        this.selfId = selfId
        this.selfName = selfName
        val adapter = adapter ?: run { Log.w(tag, "No Bluetooth adapter"); return }
        if (!adapter.isEnabled) { Log.w(tag, "Bluetooth is off"); return }

        startGattServer()
        startAdvertising()
        startScanning()
        scope.launch {
            // Serialize all GATT client work (writes + identity reads) — Android's stack
            // does not tolerate overlapping connects/operations.
            for (job in outbound) when (job) {
                is WriteJob -> sendToDevice(job.device, job.bytes)
                is IdentityReadJob -> readIdentity(job.device)
            }
        }
        _status.value = _status.value.copy(running = true)
    }

    fun stop() {
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        advertising = false
        scanning = false
        neighbors.clear()
        identityRequested.clear()
        nearby.clear()
        _nearbyPeers.value = emptyList()
        _status.value = MeshStatus(running = false, neighborCount = 0)
    }

    /**
     * Broadcast a brand-new emergency [packet] originated by this device to every
     * neighbour. The caller builds the packet (so it can reuse the same msgId on the
     * internet path for de-duplication).
     */
    fun send(packet: BlePacket) {
        addSeen(packet.msgId)
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

    // ---- GATT server (peripheral / inbound) ----

    private fun startGattServer() {
        val server = bluetoothManager?.openGattServer(context, serverCallback) ?: return
        val characteristic = BluetoothGattCharacteristic(
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
        service.addCharacteristic(characteristic)
        service.addCharacteristic(identity)
        server.addService(service)
        gattServer = server
    }

    /** "uid|name" this device reports to peers reading our identity characteristic. */
    private fun identityBytes(): ByteArray = "$selfId|$selfName".toByteArray(Charsets.UTF_8)

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == BleConstants.IDENTITY_CHARACTERISTIC_UUID) {
                val bytes = identityBytes()
                val value = if (offset >= bytes.size) ByteArray(0) else bytes.copyOfRange(offset, bytes.size)
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
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            val packet = BlePacket.fromBytes(value) ?: return
            handleIncoming(packet, device.address)
        }
    }

    private fun handleIncoming(packet: BlePacket, fromAddress: String?) {
        if (!addSeen(packet.msgId)) return // already seen -> shortest path already won
        scope.launch { _incoming.emit(packet) }
        if (packet.ttl > 1 && packet.reachCount < BleConstants.REACH_CAP) {
            enqueueToNeighbors(packet.relayed(), excludeAddress = fromAddress)
        }
    }

    // ---- Advertising ----

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true }
        override fun onStartFailure(errorCode: Int) { Log.w(tag, "Advertise failed: $errorCode") }
    }

    // ---- Scanning (central / neighbour discovery) ----

    private fun startScanning() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
        scanning = true
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            if (neighbors.put(device.address, device) == null) {
                _status.value = _status.value.copy(neighborCount = neighbors.size)
            }
            // First time we see this device, read its identity for the Nearby list.
            if (identityRequested.add(device.address)) {
                outbound.trySend(IdentityReadJob(device))
            }
        }

        override fun onScanFailed(errorCode: Int) { Log.w(tag, "Scan failed: $errorCode") }
    }

    // ---- Outbound (central write) ----

    private fun enqueueToNeighbors(packet: BlePacket, excludeAddress: String?) {
        val bytes = packet.toBytes()
        neighbors.values
            .filter { it.address != excludeAddress }
            .forEach { outbound.trySend(WriteJob(it, bytes)) }
    }

    /**
     * Connect to [device] and read its identity characteristic ("uid|name"), adding it to
     * the Nearby list. Runs on the serialized outbound worker so it never overlaps a write.
     */
    private suspend fun readIdentity(device: BluetoothDevice) {
        val identity = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<String?> { cont ->
                var settled = false
                fun finish(result: String?, gatt: BluetoothGatt?) {
                    if (settled) return
                    settled = true
                    try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(result)
                }
                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                            BluetoothProfile.STATE_DISCONNECTED -> finish(null, gatt)
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        val characteristic = gatt.getService(BleConstants.SERVICE_UUID)
                            ?.getCharacteristic(BleConstants.IDENTITY_CHARACTERISTIC_UUID)
                        if (characteristic == null) { finish(null, gatt); return }
                        gatt.readCharacteristic(characteristic)
                    }

                    @Suppress("DEPRECATION")
                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        val value = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
                        finish(value?.toString(Charsets.UTF_8), gatt)
                    }

                    @Suppress("DEPRECATION")
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
                cont.invokeOnCancellation { try { gatt.close() } catch (_: Exception) {} }
            }
        }

        if (identity == null) {
            // Let a future scan retry this device.
            identityRequested.remove(device.address)
            return
        }
        val parts = identity.split("|", limit = 2)
        val uid = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return
        val name = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Someone"
        if (uid == selfId) return
        nearby[uid] = NearbyPeer(uid, name, device.address, System.currentTimeMillis())
        _nearbyPeers.value = nearby.values.sortedBy { it.name.lowercase() }
    }

    private suspend fun sendToDevice(device: BluetoothDevice, bytes: ByteArray): Boolean {
        val ok = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                var settled = false
                fun finish(result: Boolean, gatt: BluetoothGatt?) {
                    if (settled) return
                    settled = true
                    try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(result)
                }
                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> gatt.requestMtu(BleConstants.REQUESTED_MTU)
                            BluetoothProfile.STATE_DISCONNECTED -> finish(false, gatt)
                        }
                    }

                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        gatt.discoverServices()
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        val characteristic = gatt.getService(BleConstants.SERVICE_UUID)
                            ?.getCharacteristic(BleConstants.MESSAGE_CHARACTERISTIC_UUID)
                        if (characteristic == null) { finish(false, gatt); return }
                        writeToCharacteristic(gatt, characteristic, bytes)
                    }

                    override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int,
                    ) {
                        finish(status == BluetoothGatt.GATT_SUCCESS, gatt)
                    }
                }
                val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                cont.invokeOnCancellation { try { gatt.close() } catch (_: Exception) {} }
            }
        }
        return ok ?: false
    }

    @Suppress("DEPRECATION")
    private fun writeToCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = bytes
            gatt.writeCharacteristic(characteristic)
        }
    }

    // ---- helpers ----

    private fun addSeen(msgId: String): Boolean {
        val added = seen.add(msgId)
        if (added && seen.size > 500) {
            val iter = seen.iterator()
            repeat(100) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        return added
    }

    fun shutdown() {
        stop()
        outbound.close()
        scope.cancel()
    }
}
