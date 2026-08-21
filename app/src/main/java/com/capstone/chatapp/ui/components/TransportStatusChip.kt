package com.capstone.chatapp.ui.components

import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.capstone.chatapp.data.transport.Tier

/**
 * Live indicator of which transport tier a screen's traffic is actually moving over right
 * now — Internet / Wi-Fi Direct / offline BLE / buffering — driven directly by
 * [com.capstone.chatapp.data.transport.TransportSendCoordinator.activeTier] and
 * [com.capstone.chatapp.data.transport.TransportSendCoordinator.bufferedCount], the same
 * source of truth on every screen that shows it (Home, Chat, Emergency), so the badge never
 * drifts out of sync with what actually sent a message.
 */
@Composable
fun TransportStatusChip(
    activeTier: Tier?,
    online: Boolean,
    bufferedCount: Int = 0,
    neighbors: Int = 0,
    modifier: Modifier = Modifier,
) {
    val tierLabel = when (activeTier) {
        Tier.INTERNET -> "Internet"
        Tier.WIFI_DIRECT -> "Wi-Fi Direct"
        Tier.BLE_MESH -> "Offline · BLE"
        null -> if (online) "Online" else "Offline · BLE"
    }
    val label = buildString {
        append(tierLabel)
        if (neighbors > 0) append(" · $neighbors near")
        if (bufferedCount > 0) append(" · buffering")
    }
    val color = when {
        bufferedCount > 0 -> MaterialTheme.colorScheme.error
        activeTier == Tier.INTERNET || (activeTier == null && online) -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(labelColor = color),
        modifier = modifier,
    )
}
