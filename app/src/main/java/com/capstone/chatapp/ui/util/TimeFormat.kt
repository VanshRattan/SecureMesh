package com.capstone.chatapp.ui.util

import com.google.firebase.Timestamp
import java.text.DateFormat
import java.util.Date

/**
 * Formats a chat/SOS timestamp for display next to a message bubble.
 * Pure — it only formats the value passed in, so there is no hidden clock read.
 */
fun formatTime(millis: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** Convenience for Firebase [Timestamp] message times; returns "" when null. */
fun formatTime(timestamp: Timestamp?): String =
    timestamp?.let { formatTime(it.toDate().time) } ?: ""
