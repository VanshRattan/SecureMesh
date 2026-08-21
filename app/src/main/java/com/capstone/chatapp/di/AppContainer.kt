package com.capstone.chatapp.di

import android.content.Context
import com.capstone.chatapp.data.local.ContactSecurityStore
import com.capstone.chatapp.data.local.EmergencyHistoryStore
import com.capstone.chatapp.data.local.OfflineMessageStore
import com.capstone.chatapp.data.local.StoreCarryForwardStore
import com.capstone.chatapp.data.metrics.MetricsCollector
import com.capstone.chatapp.data.repository.AuthRepository
import com.capstone.chatapp.data.repository.ChatRepository
import com.capstone.chatapp.data.repository.EmergencyRepository
import com.capstone.chatapp.data.repository.OfflineMessageRouter
import com.capstone.chatapp.data.repository.PeerKeyResolver
import com.capstone.chatapp.data.repository.SettingsRepository
import com.capstone.chatapp.data.repository.UserRepository
import com.capstone.chatapp.data.security.CryptoManager
import com.capstone.chatapp.data.transport.EnergyMonitor
import com.capstone.chatapp.data.transport.InternetTransport
import com.capstone.chatapp.data.transport.NetworkMonitor
import com.capstone.chatapp.data.transport.StoreCarryForwardQueue
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.Transport
import com.capstone.chatapp.data.transport.TransportArbiter
import com.capstone.chatapp.data.transport.TransportSendCoordinator
import com.capstone.chatapp.data.transport.ble.BleMeshManager
import com.capstone.chatapp.data.transport.ble.BleTransport
import com.capstone.chatapp.data.transport.wifidirect.WifiDirectManager
import com.capstone.chatapp.data.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Tiny manual DI container — holds the single instances of each repository and the
 * app-wide BLE mesh + network monitor. Created once in [com.capstone.chatapp.ChatApp]
 * and reached from ViewModels via [appContainer]. Keeps wiring simple (no Hilt).
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    // Outlives every screen -- backs the arbiter's retry tick + active-tier tracking.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val authRepository: AuthRepository = AuthRepository()
    val userRepository: UserRepository = UserRepository()
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val emergencyHistoryStore: EmergencyHistoryStore = EmergencyHistoryStore(appContext)

    // E2E encryption: identity key pair + local cache of peers' public keys / verified flags.
    val cryptoManager: CryptoManager = CryptoManager(appContext)
    val contactSecurityStore: ContactSecurityStore = ContactSecurityStore(appContext)

    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext)
    // Singleton so the UI and the foreground service observe/drive the same mesh.
    val bleMeshManager: BleMeshManager = BleMeshManager(appContext)
    // Singleton for the same reason as bleMeshManager: one Wi-Fi Direct group per process.
    val wifiDirectManager: WifiDirectManager = WifiDirectManager(appContext)

    // The Transport layer: everything above this talks Packets, never a bearer directly.
    val internetTransport: InternetTransport = InternetTransport()
    val bleTransport: BleTransport = BleTransport(bleMeshManager)
    val wifiDirectTransport: WifiDirectTransport = WifiDirectTransport(wifiDirectManager)

    // Per-hop arbiter + reliable-delivery pipeline: repositories/ViewModels no longer pick a
    // tier themselves, they hand a Packet to transportSendCoordinator and it decides.
    val energyMonitor: EnergyMonitor = EnergyMonitor(appContext)

    // Evaluation instrumentation (CLAUDE.md §5.5): compiled in everywhere, but a no-op in a
    // release build (see MetricsCollector's own doc comment for why BuildConfig.DEBUG rather
    // than a runtime toggle).
    val metricsCollector: MetricsCollector =
        MetricsCollector(appContext, energyMonitor, bleMeshManager, wifiDirectManager).also { it.start(appScope) }

    val transportArbiter: TransportArbiter =
        TransportArbiter(bleMeshManager, wifiDirectManager, networkMonitor, energyMonitor)
    val storeCarryForwardStore: StoreCarryForwardStore = StoreCarryForwardStore(appContext)
    val storeCarryForwardQueue: StoreCarryForwardQueue = StoreCarryForwardQueue(storeCarryForwardStore)
    val transportSendCoordinator: TransportSendCoordinator = TransportSendCoordinator(
        transportArbiter,
        mapOf<Tier, Transport>(
            Tier.INTERNET to internetTransport,
            Tier.WIFI_DIRECT to wifiDirectTransport,
            Tier.BLE_MESH to bleTransport,
        ),
        storeCarryForwardQueue,
        metricsCollector,
    ).also { it.start(appScope) }

    val peerKeyResolver: PeerKeyResolver = PeerKeyResolver(userRepository, contactSecurityStore)
    val offlineMessageStore: OfflineMessageStore = OfflineMessageStore(appContext)

    val chatRepository: ChatRepository =
        ChatRepository(transportSendCoordinator, peerKeyResolver, cryptoManager, offlineMessageStore)
    val emergencyRepository: EmergencyRepository = EmergencyRepository()

    // Receive half of offline 1:1 messaging: decrypts targeted packets that arrive over BLE/
    // Wi-Fi Direct, persists them, and ACKs the sender. App-scoped so it runs regardless of
    // which screen (if any) is open, same as transportSendCoordinator above.
    val offlineMessageRouter: OfflineMessageRouter = OfflineMessageRouter(
        authRepository,
        bleTransport,
        wifiDirectTransport,
        transportSendCoordinator,
        cryptoManager,
        peerKeyResolver,
        offlineMessageStore,
        metricsCollector,
    ).also { it.start(appScope) }
}

/** Convenience accessor from any Context. */
fun Context.appContainer(): AppContainer =
    (applicationContext as com.capstone.chatapp.ChatApp).container
