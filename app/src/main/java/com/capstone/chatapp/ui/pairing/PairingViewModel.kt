package com.capstone.chatapp.ui.pairing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.chatapp.data.security.PairingPayload
import com.capstone.chatapp.data.security.QrCodec
import com.capstone.chatapp.data.security.SafetyNumber
import com.capstone.chatapp.di.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairingUiState(
    val myQrText: String = "",
    val peerUid: String? = null,
    val peerName: String? = null,
    val peerVerified: Boolean = false,
    val safetyNumber: String? = null,
    val scanning: Boolean = false,
    val message: String? = null,
)

/**
 * Drives QR pairing: shows this device's own QR (uid + name + public key) and, when scanning,
 * decodes someone else's and treats a successful in-person scan as verification — no separate
 * safety-number comparison is required after that, since the key came straight off their screen.
 * [SafetyNumber] is offered as the fallback for verifying a contact without a shared QR moment.
 */
class PairingViewModel(
    app: Application,
    initialPeerUid: String?,
    initialPeerName: String?,
) : AndroidViewModel(app) {

    private val container = app.appContainer()
    private val myUid = container.authRepository.currentUid.orEmpty()
    private var myName: String = container.authRepository.currentEmail?.substringBefore('@') ?: "Me"

    private val _state = MutableStateFlow(PairingUiState(peerUid = initialPeerUid, peerName = initialPeerName))
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { container.userRepository.getUser(myUid) }.getOrNull()?.let { user ->
                if (user.displayName.isNotBlank()) myName = user.displayName
            }
            val myPub = runCatching { container.cryptoManager.publicKeyBase64() }.getOrDefault("")
            val qrText = if (myPub.isNotBlank()) QrCodec.encode(PairingPayload(myUid, myName, myPub)) else ""
            _state.update { it.copy(myQrText = qrText) }
        }
        initialPeerUid?.let { peerUid ->
            viewModelScope.launch {
                container.contactSecurityStore.isVerified(myUid, peerUid).collect { verified ->
                    _state.update { it.copy(peerVerified = verified) }
                }
            }
            viewModelScope.launch { refreshSafetyNumber(peerUid) }
        }
    }

    private suspend fun refreshSafetyNumber(peerUid: String) {
        val peerPub = container.contactSecurityStore.getCachedPeerPublicKey(myUid, peerUid)
            ?: runCatching { container.userRepository.getUser(peerUid)?.pubKey }.getOrNull()?.takeIf { it.isNotBlank() }
        val myPub = runCatching { container.cryptoManager.publicKeyBase64() }.getOrNull()
        if (peerPub != null && myPub != null) {
            _state.update { it.copy(safetyNumber = SafetyNumber.compute(myUid, myPub, peerUid, peerPub)) }
        }
    }

    fun setScanning(scanning: Boolean) = _state.update { it.copy(scanning = scanning) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Called with each decoded QR string from the camera analyzer. Returns true once it is a
     *  valid pairing code (whether or not it's usable), so the scanner stops feeding frames. */
    fun onQrScanned(raw: String): Boolean {
        val payload = PairingPayload.parse(raw) ?: return false
        if (payload.uid == myUid) {
            _state.update { it.copy(message = "That's your own QR code", scanning = false) }
            return true
        }
        viewModelScope.launch {
            container.contactSecurityStore.cachePeerPublicKey(myUid, payload.uid, payload.pubKeyBase64)
            // An in-person QR scan IS the verification step — the key came straight off their
            // device's screen, so there is no swap-in-transit to defend against here.
            container.contactSecurityStore.setVerified(myUid, payload.uid, true)
            _state.update {
                it.copy(
                    peerUid = payload.uid,
                    peerName = payload.name,
                    scanning = false,
                    message = "${payload.name.ifBlank { "Contact" }} verified",
                )
            }
            refreshSafetyNumber(payload.uid)
        }
        return true
    }
}
