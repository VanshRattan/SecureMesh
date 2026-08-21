package com.capstone.chatapp.ui.emergency

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.capstone.chatapp.data.transport.Tier
import com.capstone.chatapp.data.transport.ble.BlePermissions
import com.capstone.chatapp.ui.components.LoadingButton
import com.capstone.chatapp.ui.util.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyScreen(onBack: () -> Unit) {
    val vm: EmergencyViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var draft by rememberSaveable { mutableStateOf("I need help") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }

    // What is currently stopping the mesh, and what we have already prompted for. The
    // second half matters: without it, declining a prompt re-opens it forever.
    var blocker by remember { mutableStateOf(MeshBlocker.NONE) }
    var promptedFor by remember { mutableStateOf<MeshBlocker?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        vm.onPermissionResult(result)
        blocker = vm.startMeshIfReady()
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The result code is unreliable across OEMs; just re-check the adapter.
        blocker = vm.startMeshIfReady()
    }
    val locationSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        blocker = vm.startMeshIfReady()
    }

    // Re-check on every resume: Bluetooth, permissions and the location toggle can all
    // be changed from outside the app while this screen sits in the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) blocker = vm.startMeshIfReady()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(blocker) {
        if (blocker == promptedFor) return@LaunchedEffect
        when (blocker) {
            MeshBlocker.PERMISSIONS_MISSING -> {
                promptedFor = blocker
                // Ask for the optional notification permission in the same pass, but a
                // denial there never blocks the mesh (see EmergencyViewModel).
                permissionLauncher.launch(BlePermissions.required())
            }
            MeshBlocker.BLUETOOTH_OFF -> {
                promptedFor = blocker
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            MeshBlocker.LOCATION_SERVICE_OFF -> {
                promptedFor = blocker
                locationSettingsLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            MeshBlocker.NONE, MeshBlocker.NO_BLUETOOTH_HARDWARE -> promptedFor = blocker
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Emergency Mode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    StatusBadge(activeTier = state.activeTier, online = state.online, neighbors = state.neighborCount)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            InfoBanner(state)

            if (state.items.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "No emergency messages yet.\nSend an SOS or wait for nearby alerts.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.msgId }) { item -> EmergencyBubble(item) }
                }
            }

            SosBar(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    vm.sendSos(draft)
                    draft = ""
                },
            )
        }
    }
}

/** The arbiter's currently-active tier is the source of truth for this badge; the old
 * plain online/offline flag is only a fallback for the moment before the first reading
 * arrives. */
@Composable
private fun StatusBadge(activeTier: Tier?, online: Boolean, neighbors: Int) {
    val label = when (activeTier) {
        Tier.INTERNET -> "Internet"
        Tier.WIFI_DIRECT -> "Wi-Fi Direct"
        Tier.BLE_MESH -> "Offline · BLE"
        null -> if (online) "Online" else "Offline · BLE"
    }
    val color = if (activeTier == Tier.INTERNET || (activeTier == null && online)) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        label = { Text(if (neighbors > 0) "$label · $neighbors near" else label) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color),
        modifier = Modifier.padding(end = 8.dp),
    )
}

/**
 * Says exactly what the mesh is doing, because "Starting Bluetooth mesh…" forever was
 * indistinguishable from a permission denial, a dead advertiser, or Bluetooth being off.
 */
@Composable
private fun InfoBanner(state: EmergencyUiState) {
    val text = when {
        state.blocker == MeshBlocker.NO_BLUETOOTH_HARDWARE ->
            "This phone has no Bluetooth — offline SOS is unavailable."
        state.blocker == MeshBlocker.PERMISSIONS_MISSING ->
            "Allow Nearby devices so this phone can reach others over Bluetooth."
        state.blocker == MeshBlocker.BLUETOOTH_OFF ->
            "Bluetooth is off — turn it on to send or receive an SOS offline."
        state.blocker == MeshBlocker.LOCATION_SERVICE_OFF ->
            "Turn on Location — this Android version needs it to find nearby phones."
        !state.meshRunning ->
            state.meshError ?: "Starting Bluetooth mesh…"
        // Running but nobody can find us: a scan-only phone never receives anything.
        !state.advertising ->
            "Bluetooth mesh is listening, but this phone cannot advertise, so others cannot reach it."
        state.online ->
            "You're online — SOS will be sent over the internet AND Bluetooth (${state.neighborCount} nearby)."
        state.neighborCount == 0 ->
            "No internet — searching for nearby phones. An SOS you send now is delivered as soon as one is found."
        else ->
            "No internet — SOS will be sent over Bluetooth to nearby phones (${state.neighborCount} found)."
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmergencyBubble(item: EmergencyItem) {
    // Mine on the right, others on the left — like a chat. SOS keeps its red intent:
    // mine = primaryContainer, others = soft error tint.
    val container = if (item.mine) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.errorContainerOrError()
    val onContainer = if (item.mine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (item.mine) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            color = container,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (item.mine) 16.dp else 4.dp,
                bottomEnd = if (item.mine) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.mine) "🆘 You" else "🆘 ${item.senderName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (!item.mine && item.hops > 0) {
                        Text(
                            "relayed ${item.hops} hop${if (item.hops == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(item.text, style = MaterialTheme.typography.bodyLarge, color = onContainer)
                val time = formatTime(item.timestamp)
                if (time.isNotEmpty()) {
                    Text(
                        text = time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
    }
}

@Composable
private fun SosBar(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Emergency message") },
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.error,
                    cursorColor = MaterialTheme.colorScheme.error,
                ),
            )
            LoadingButton(
                text = "🆘  SEND SOS",
                onClick = onSend,
                enabled = draft.isNotBlank(),
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        }
    }
}

/** errorContainer isn't in our custom scheme, so fall back to a soft error tint. */
@Composable
private fun androidx.compose.material3.ColorScheme.errorContainerOrError(): Color =
    error.copy(alpha = 0.12f)
