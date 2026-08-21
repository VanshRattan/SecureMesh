package com.capstone.chatapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.chatapp.data.local.ContactSecurityStore
import com.capstone.chatapp.data.model.ChatSummary
import com.capstone.chatapp.data.repository.AuthRepository
import com.capstone.chatapp.data.repository.ChatRepository
import com.capstone.chatapp.data.transport.NetworkMonitor
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.TransportSendCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val chats: List<ChatSummary> = emptyList(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val activeTier: Tier? = null,
    val online: Boolean = false,
    val bufferedCount: Int = 0,
)

/** Loads the current user's conversations for the Home list, plus the live transport
 * indicator shown in the top bar. */
class HomeViewModel(
    authRepository: AuthRepository,
    chatRepository: ChatRepository,
    transportSendCoordinator: TransportSendCoordinator,
    contactSecurityStore: ContactSecurityStore,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val myUid: String = authRepository.currentUid.orEmpty()

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                chatRepository.observeRecentChats(myUid),
                contactSecurityStore.verifiedPeerUids(myUid),
            ) { chats, verified ->
                chats.map { it.copy(verified = verified.contains(it.peerUid)) }
            }
                .catch { e -> _state.update { it.copy(loading = false, errorMessage = e.message ?: "Error loading chats") } }
                .collect { chats -> _state.update { it.copy(chats = chats, loading = false) } }
        }
        viewModelScope.launch {
            transportSendCoordinator.activeTier.collect { tier -> _state.update { it.copy(activeTier = tier) } }
        }
        viewModelScope.launch {
            transportSendCoordinator.bufferedCount.collect { count -> _state.update { it.copy(bufferedCount = count) } }
        }
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online -> _state.update { it.copy(online = online) } }
        }
    }

    fun consumeError() = _state.update { it.copy(errorMessage = null) }
}
