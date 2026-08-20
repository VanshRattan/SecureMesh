package com.capstone.chatapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.capstone.chatapp.di.appContainer
import com.capstone.chatapp.navigation.AppNavHost
import com.capstone.chatapp.ui.theme.AppTheme
import com.capstone.chatapp.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * Single Activity hosting the whole Compose app.
 * Reads the persisted theme from DataStore and applies it, then shows the NavHost.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = appContainer()
        val startLoggedIn = container.authRepository.isLoggedIn

        // Cold start with an existing session (Login/Signup are skipped): bind the identity
        // key pair here instead, and refresh the published public key in case this is a new
        // install reusing an existing account.
        if (startLoggedIn) {
            container.authRepository.currentUid?.let { uid ->
                container.cryptoManager.bind(uid)
                lifecycleScope.launch {
                    runCatching {
                        container.userRepository.publishPublicKey(uid, container.cryptoManager.publicKeyBase64())
                    }
                }
            }
        }

        setContent {
            val themeMode by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

            AppTheme(themeMode = themeMode) {
                AppNavHost(startLoggedIn = startLoggedIn)
            }
        }
    }
}
