package com.capstone.chatapp.ui.signup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capstone.chatapp.data.repository.AuthRepository
import com.capstone.chatapp.data.repository.UserRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignupUiState(
    val email: String = "",
    val password: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val signedUp: Boolean = false,
)

/** Holds signup form state, creates the account, and saves the user profile. */
class SignupViewModel(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SignupUiState())
    val state: StateFlow<SignupUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, emailError = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, passwordError = null) }
    fun consumeError() = _state.update { it.copy(errorMessage = null) }

    fun signup() {
        val current = _state.value
        val email = current.email.trim()
        val password = current.password

        var valid = true
        if (email.isEmpty()) { _state.update { it.copy(emailError = "Please enter your email") }; valid = false }
        if (password.isEmpty()) {
            _state.update { it.copy(passwordError = "Please enter a password") }; valid = false
        } else if (password.length < 6) {
            _state.update { it.copy(passwordError = "Password must be at least 6 characters") }; valid = false
        }
        if (!valid) return

        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val uid = authRepository.signUp(email, password)
                userRepository.saveUser(uid, email)
                _state.update { it.copy(isLoading = false, signedUp = true) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, errorMessage = e.message ?: "Sign up failed") }
            }
        }
    }
}
