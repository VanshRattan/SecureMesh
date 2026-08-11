package com.capstone.chatapp.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.chatapp.data.repository.AuthRepository
import com.capstone.chatapp.data.repository.SettingsRepository
import com.capstone.chatapp.data.repository.UserRepository
import com.capstone.chatapp.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val email: String = "",
    val username: String = "",
    val usernameDraft: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val message: String? = null,
    val loggedOut: Boolean = false,
)

/** Profile: shows account info, edits username, switches theme, and logs out. */
class ProfileViewModel(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    init {
        loadProfile()
    }

    private fun loadProfile() {
        val uid = authRepository.currentUid
        if (uid == null) {
            _state.update { it.copy(isLoading = false, loggedOut = true) }
            return
        }
        viewModelScope.launch {
            try {
                val user = userRepository.getUser(uid)
                _state.update {
                    it.copy(
                        isLoading = false,
                        email = user?.email ?: authRepository.currentEmail.orEmpty(),
                        username = user?.username.orEmpty(),
                        usernameDraft = user?.username.orEmpty(),
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, email = authRepository.currentEmail.orEmpty(), message = "Could not load profile")
                }
            }
        }
    }

    fun onUsernameDraftChange(value: String) = _state.update { it.copy(usernameDraft = value) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun saveUsername() {
        val uid = authRepository.currentUid ?: return
        val newName = _state.value.usernameDraft.trim()
        if (newName == _state.value.username) {
            _state.update { it.copy(message = "Username unchanged") }
            return
        }
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                userRepository.updateUsername(uid, newName)
                _state.update { it.copy(isSaving = false, username = newName, message = "Username updated") }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, message = "Failed to update username") }
            }
        }
    }

    fun logout() {
        authRepository.signOut()
        _state.update { it.copy(loggedOut = true) }
    }
}
