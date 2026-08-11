package com.capstone.chatapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * App palette. Chosen for high contrast / readability (elder-friendly) in both
 * light and dark modes. Primary = trustworthy blue; Emergency = strong red used
 * only for the SOS action.
 */

// Brand
val Blue500 = Color(0xFF1565C0)
val Blue300 = Color(0xFF5E92F3)
val Blue900 = Color(0xFF003C8F)

// Emergency accent
val EmergencyRed = Color(0xFFD32F2F)
val EmergencyRedDark = Color(0xFFFF6659)

// Light scheme neutrals
val LightBackground = Color(0xFFFDFDFD)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEF1F5)
val LightOnBackground = Color(0xFF15181C)
val LightOnSurfaceVariant = Color(0xFF43474E)
val LightOutline = Color(0xFF73777F)

// Dark scheme neutrals
val DarkBackground = Color(0xFF111316)
val DarkSurface = Color(0xFF191C1F)
val DarkSurfaceVariant = Color(0xFF272A2E)
val DarkOnBackground = Color(0xFFE3E2E6)
val DarkOnSurfaceVariant = Color(0xFFC3C7CF)
val DarkOutline = Color(0xFF8D9199)

// Chat bubble colors (theme-aware, referenced from ChatScreen)
val BubbleSentLight = Color(0xFF1565C0)
val BubbleSentDark = Color(0xFF3B6FBF)
val BubbleReceivedLight = Color(0xFFE7EBF0)
val BubbleReceivedDark = Color(0xFF2B2F34)
