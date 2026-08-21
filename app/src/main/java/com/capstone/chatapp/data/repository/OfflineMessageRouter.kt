package com.capstone.chatapp.data.repository

import com.capstone.chatapp.data.local.OfflineMessageStore
import com.capstone.chatapp.data.metrics.MetricsCollector
import com.capstone.chatapp.data.model.DeliveryState
import com.capstone.chatapp.data.model.Message
import com.capstone.chatapp.data.security.CryptoManager
import com.capstone.chatapp.data.transport.Packet
import com.capstone.chatapp.data.transport.Priority
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.Transport
import com.capstone.chatapp.data.transport.TransportSendCoordinator
import com.google.firebase.Timestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val ACK_TTL = 10

/**
 * The receive half of offline 1:1 messaging: listens to targeted [Packet]s that arrive over
 * BLE or Wi-Fi Direct (never Internet — Firestore delivery is observed per-chat by
 * [ChatRepository] instead), decrypts them, appends them to [offlineMessageStore] so
 * [ChatRepository.observeMessages] picks them up, and sends a delivery-receipt ([Packet.ack])
 * back to the sender so their own thread can upgrade Sent -> Delivered. Also the other side of
 * that handshake: an incoming ack just bumps the matching locally-stored message's state,
 * nothing more.
 *
 * Started once from `AppContainer` with the app-lifetime scope, same as
 * [TransportSendCoordinator] — a screen being open or closed must not affect whether an
 * offline message from a Nearby peer gets received.
 */
class OfflineMessageRouter(
    private val authRepository: AuthRepository,
    private val bleTransport: Transport,
    private val wifiDirectTransport: Transport,
    private val transportSendCoordinator: TransportSendCoordinator,
    private val cryptoManager: CryptoManager,
    private val peerKeyResolver: PeerKeyResolver,
    private val offlineMessageStore: OfflineMessageStore,
    private val metricsCollector: MetricsCollector,
) {

    fun start(scope: CoroutineScope) {
        // Collected as two separately-tagged flows (rather than merge()) so every packet
        // carries the tier it actually arrived over for MetricsCollector -- a merged flow
        // would lose that, since neither BlePacket nor a targeted Packet's own tierTag
        // reflects which bearer the arbiter picked for this particular hop.
        scope.launch { bleTransport.incoming.collect { packet -> runCatching { handlePacket(packet, Tier.BLE_MESH) } } }
        scope.launch { wifiDirectTransport.incoming.collect { packet -> runCatching { handlePacket(packet, Tier.WIFI_DIRECT) } } }
    }

    private suspend fun handlePacket(packet: Packet, tier: Tier) {
        val myUid = authRepository.currentUid ?: return
        val destId = packet.destId ?: return // broadcasts (the SOS) aren't this router's concern
        if (destId != myUid) return // not addressed to this device -- just a relay hop
        val srcId = packet.srcId ?: return
        val chatId = Message.getChatId(myUid, srcId)

        if (packet.ack) {
            metricsCollector.recordReceive(packet, tier)
            offlineMessageStore.updateState(chatId, packet.msgId, DeliveryState.DELIVERED)
            return
        }

        val peerPubKey = runCatching { peerKeyResolver.resolve(myUid, srcId) }.getOrNull() ?: return
        val text = runCatching {
            String(cryptoManager.decryptFrom(srcId, peerPubKey, packet.payload), Charsets.UTF_8)
        }.getOrNull() ?: return

        metricsCollector.recordReceive(packet, tier)
        offlineMessageStore.append(chatId, Message(packet.msgId, srcId, text, Timestamp.now(), DeliveryState.DELIVERED))

        val ack = Packet(
            msgId = packet.msgId,
            destId = srcId,
            srcId = myUid,
            ttl = ACK_TTL,
            priority = Priority.NORMAL,
            tierTag = Tier.BLE_MESH,
            nonce = ByteArray(0),
            payload = ByteArray(0),
            ack = true,
        )
        transportSendCoordinator.send(ack)
    }
}
