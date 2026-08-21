package com.capstone.chatapp.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Small per-contact trust indicator: a filled check once a contact's public key has been
 * confirmed out-of-band (QR pairing or a matching safety number — see
 * `data/local/ContactSecurityStore.kt`), otherwise a quiet unverified warning. Shown next to a
 * name on Home, Discover, and in the Chat top bar so "who am I actually talking to" is never
 * hidden a tap away in Profile → Verify.
 */
@Composable
fun VerifiedBadge(verified: Boolean, modifier: Modifier = Modifier) {
    if (verified) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Verified contact",
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(16.dp),
        )
    } else {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = "Unverified contact",
            tint = MaterialTheme.colorScheme.error,
            modifier = modifier.size(16.dp),
        )
    }
}
