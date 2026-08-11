package com.capstone.chatapp.data.model

/**
 * A registered user. Backed by Firestore document users/{uid}.
 * `username` is optional; falls back to the email's local part when absent.
 */
data class User(
    val uid: String = "",
    val email: String = "",
    val username: String = "",
) {
    /** Best display name: explicit username, else the part of the email before '@'. */
    val displayName: String
        get() = username.ifBlank { email.substringBefore('@') }
}
