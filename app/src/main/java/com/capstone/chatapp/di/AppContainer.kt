package com.capstone.chatapp.di

import android.content.Context
import com.capstone.chatapp.data.local.EmergencyHistoryStore
import com.capstone.chatapp.data.repository.AuthRepository
import com.capstone.chatapp.data.repository.ChatRepository
import com.capstone.chatapp.data.repository.EmergencyRepository
import com.capstone.chatapp.data.repository.SettingsRepository
import com.capstone.chatapp.data.repository.UserRepository
import com.capstone.chatapp.data.transport.NetworkMonitor
import com.capstone.chatapp.data.transport.ble.BleMeshManager

/**
 * Tiny manual DI container — holds the single instances of each repository and the
 * app-wide BLE mesh + network monitor. Created once in [com.capstone.chatapp.ChatApp]
 * and reached from ViewModels via [appContainer]. Keeps wiring simple (no Hilt).
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val authRepository: AuthRepository = AuthRepository()
    val userRepository: UserRepository = UserRepository()
    val chatRepository: ChatRepository = ChatRepository()
    val emergencyRepository: EmergencyRepository = EmergencyRepository()
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val emergencyHistoryStore: EmergencyHistoryStore = EmergencyHistoryStore(appContext)

    val networkMonitor: NetworkMonitor = NetworkMonitor(appContext)
    // Singleton so the UI and the foreground service observe/drive the same mesh.
    val bleMeshManager: BleMeshManager = BleMeshManager(appContext)
}

/** Convenience accessor from any Context. */
fun Context.appContainer(): AppContainer =
    (applicationContext as com.capstone.chatapp.ChatApp).container
