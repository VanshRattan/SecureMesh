package com.capstone.chatapp.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.chatapp.data.model.User

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    onOpenChat: (peerUid: String, peerName: String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: DiscoverViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbar.showSnackbar(it); vm.consumeError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Discover") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("All Users") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(if (state.nearby.isEmpty()) "Nearby" else "Nearby (${state.nearby.size})") },
                )
            }
            when (tab) {
                0 -> AllUsersTab(vm, state, onOpenChat)
                else -> NearbyTab(state, onOpenChat)
            }
        }
    }
}

@Composable
private fun AllUsersTab(
    vm: DiscoverViewModel,
    state: DiscoverUiState,
    onOpenChat: (String, String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { Text("Search by name or email") },
            singleLine = true,
        )
        when {
            state.loadingUsers -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            else -> {
                val users = vm.filteredUsers()
                if (users.isEmpty()) {
                    CenterMessage("No users found.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(users, key = { it.uid }) { user ->
                            PersonRow(
                                name = user.displayName,
                                subtitle = user.email,
                                onClick = { onOpenChat(user.uid, user.displayName) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyTab(state: DiscoverUiState, onOpenChat: (String, String) -> Unit) {
    when {
        state.nearby.isNotEmpty() -> {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.nearby, key = { it.uid }) { peer ->
                    PersonRow(
                        name = peer.name,
                        subtitle = "Near you · Bluetooth",
                        onClick = { onOpenChat(peer.uid, peer.name) },
                    )
                }
            }
        }
        else -> {
            val msg = if (state.meshRunning) {
                if (state.neighborCount > 0) {
                    "${state.neighborCount} device(s) nearby — identifying…"
                } else {
                    "Looking for people nearby over Bluetooth…"
                }
            } else {
                "Open Emergency Mode once to grant Bluetooth, then people nearby will appear here."
            }
            CenterMessage(msg)
        }
    }
}

@Composable
private fun PersonRow(name: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
