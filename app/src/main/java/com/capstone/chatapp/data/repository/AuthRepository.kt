package com.capstone.chatapp.data.repository

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await

/**
 * Wraps FirebaseAuth so the UI/ViewModels never touch Firebase directly.
 * Suspend functions throw on failure; callers map the exception to a message.
 */
class AuthRepository(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) {

    val currentUid: String? get() = auth.currentUser?.uid
    val currentEmail: String? get() = auth.currentUser?.email
    val isLoggedIn: Boolean get() = auth.currentUser != null

    suspend fun signIn(email: String, password: String): String {
        val result = auth.signInWithEmailAndPassword(email, password).await()
        return result.user?.uid ?: error("Sign-in returned no user")
    }

    suspend fun signUp(email: String, password: String): String {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        return result.user?.uid ?: error("Sign-up returned no user")
    }

    fun signOut() = auth.signOut()
}
