package com.capstone.chatapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.chatapp.data.model.ChatSummary
import com.capstone.chatapp.data.repository.AuthRepository
import com.capstone.chatapp.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val chats: List<ChatSummary> = emptyList(),
    val loading: Boolean = true,
    val errorMessage: String? = null,
)

/** Loads the current user's conversations for the Home list. */
class HomeViewModel(
    authRepository: AuthRepository,
    chatRepository: ChatRepository,
) : ViewModel() {

    private val myUid: String = authRepository.currentUid.orEmpty()

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            chatRepository.observeRecentChats(myUid)
                .catch { e -> _state.update { it.copy(loading = false, errorMessage = e.message ?: "Error loading chats") } }
                .collect { chats -> _state.update { it.copy(chats = chats, loading = false) } }
        }
    }

    fun consumeError() = _state.update { it.copy(errorMessage = null) }
}
