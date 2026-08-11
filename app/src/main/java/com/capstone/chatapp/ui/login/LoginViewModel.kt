package com.capstone.chatapp.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.chatapp.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val loggedIn: Boolean = false,
)

/** Holds login form state (survives rotation) and performs sign-in. */
class LoginViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, emailError = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, passwordError = null) }
    fun consumeError() = _state.update { it.copy(errorMessage = null) }

    fun login() {
        val current = _state.value
        val email = current.email.trim()
        val password = current.password

        var valid = true
        if (email.isEmpty()) { _state.update { it.copy(emailError = "Please enter your email") }; valid = false }
        if (password.isEmpty()) { _state.update { it.copy(passwordError = "Please enter your password") }; valid = false }
        if (!valid) return

        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                authRepository.signIn(email, password)
                _state.update { it.copy(isLoading = false, loggedIn = true) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = e.message ?: "Login failed") }
            }
        }
    }
}
