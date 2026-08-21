package com.capstone.chatapp.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.chatapp.data.local.ContactSecurityStore
import com.capstone.chatapp.data.model.Message
import com.capstone.chatapp.data.repository.AuthRepository
import com.capstone.chatapp.data.repository.ChatRepository
import com.capstone.chatapp.data.repository.UserRepository
import com.capstone.chatapp.data.transport.SendResult
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.TransportSendCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val verified: Boolean = false,
    val activeTier: Tier? = null,
    val bufferedCount: Int = 0,
)

/**
 * Observes and sends messages for the 1:1 chat between the current user and [peerUid].
 * chatId is deterministic (see [Message.getChatId]) so both users share one thread. Backed by
 * [ChatRepository], which now transparently merges Firestore (online) history with any
 * offline (BLE/Wi-Fi Direct) messages for the same thread — this screen works the same way
 * whether the peer was opened from Discover's online directory or its Nearby (BLE) tab.
 */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    authRepository: AuthRepository,
    userRepository: UserRepository,
    transportSendCoordinator: TransportSendCoordinator,
    contactSecurityStore: ContactSecurityStore,
    private val peerUid: String,
    private val peerName: String,
) : ViewModel() {

    val currentUid: String = authRepository.currentUid.orEmpty()
    private val chatId: String = Message.getChatId(currentUid, peerUid)

    // My display name, stamped onto the chat summary so the peer sees a real name.
    private var myName: String = authRepository.currentEmail?.substringBefore('@') ?: "Me"

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { userRepository.getUser(currentUid) }.getOrNull()?.let { user ->
                if (user.displayName.isNotBlank()) myName = user.displayName
            }
        }
        viewModelScope.launch {
            chatRepository.observeMessages(chatId, currentUid, peerUid)
                .catch { e -> _state.update { it.copy(errorMessage = e.message ?: "Error loading messages") } }
                .collect { messages -> _state.update { it.copy(messages = messages) } }
        }
        viewModelScope.launch {
            contactSecurityStore.isVerified(currentUid, peerUid).collect { verified ->
                _state.update { it.copy(verified = verified) }
            }
        }
        viewModelScope.launch {
            transportSendCoordinator.activeTier.collect { tier -> _state.update { it.copy(activeTier = tier) } }
        }
        viewModelScope.launch {
            transportSendCoordinator.bufferedCount.collect { count -> _state.update { it.copy(bufferedCount = count) } }
        }
    }

    fun consumeError() = _state.update { it.copy(errorMessage = null) }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _state.update { it.copy(isSending = true) }
        viewModelScope.launch {
            try {
                val result = chatRepository.sendMessage(
                    chatId = chatId,
                    senderId = currentUid,
                    senderName = myName,
                    peerId = peerUid,
                    peerName = peerName,
                    text = trimmed,
                )
                if (result == SendResult.QUEUED) {
                    _state.update {
                        it.copy(errorMessage = "No connection right now — this message will send automatically")
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Failed to send message") }
            } finally {
                _state.update { it.copy(isSending = false) }
            }
        }
    }
}
