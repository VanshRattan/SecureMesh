package com.capstone.chatapp.ui.signup

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.capstone.chatapp.di.appContainer
import com.capstone.chatapp.ui.components.AppTextField
import com.capstone.chatapp.ui.components.AuthScaffold
import com.capstone.chatapp.ui.components.LoadingButton

@Composable
fun SignupScreen(
    onSignedUp: () -> Unit,
    onNavigateLogin: () -> Unit,
) {
    val container = LocalContext.current.appContainer()
    val vm: SignupViewModel = viewModel(factory = viewModelFactory {
        initializer { SignupViewModel(container.authRepository, container.userRepository) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.signedUp) { if (state.signedUp) onSignedUp() }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbar.showSnackbar(it); vm.consumeError() }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { _ ->
        AuthScaffold {
            Text(
                text = "Create account",
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Sign up with your email and a password (at least 6 characters).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            AppTextField(
                value = state.email,
                onValueChange = vm::onEmailChange,
                label = "Email",
                isEmail = true,
                errorText = state.emailError,
                imeAction = ImeAction.Next,
            )
            AppTextField(
                value = state.password,
                onValueChange = vm::onPasswordChange,
                label = "Password",
                isPassword = true,
                errorText = state.passwordError,
                imeAction = ImeAction.Done,
            )
            Spacer(Modifier.height(8.dp))

            LoadingButton(
                text = "Create Account",
                onClick = vm::signup,
                loading = state.isLoading,
            )
            TextButton(onClick = onNavigateLogin) {
                Text("Already have an account? Log in", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
