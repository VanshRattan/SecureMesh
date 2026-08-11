package com.capstone.chatapp.data.repository

import com.capstone.chatapp.data.model.User
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Firestore access for the `users` collection: users/{uid} = { email, username }.
 */
class UserRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    private val users get() = firestore.collection("users")

    /** Create/merge the user profile on signup. */
    suspend fun saveUser(uid: String, email: String, username: String = "") {
        val data = mutableMapOf<String, Any>("email" to email)
        if (username.isNotBlank()) data["username"] = username
        users.document(uid).set(data, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    suspend fun getUser(uid: String): User? {
        val doc = users.document(uid).get().await()
        if (!doc.exists()) return null
        return User(
            uid = uid,
            email = doc.getString("email") ?: "",
            username = doc.getString("username") ?: "",
        )
    }

    /** All registered users except [excludeUid] — powers the Discover directory. */
    suspend fun listUsers(excludeUid: String): List<User> {
        val snapshot = users.get().await()
        return snapshot.documents
            .filter { it.id != excludeUid }
            .map { doc ->
                User(
                    uid = doc.id,
                    email = doc.getString("email") ?: "",
                    username = doc.getString("username") ?: "",
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }

    /** Look up a user by email (used to start a 1:1 chat). Returns null if none. */
    suspend fun findByEmail(email: String): User? {
        val snapshot = users.whereEqualTo("email", email).limit(1).get().await()
        val doc = snapshot.documents.firstOrNull() ?: return null
        return User(
            uid = doc.id,
            email = doc.getString("email") ?: "",
            username = doc.getString("username") ?: "",
        )
    }

    suspend fun updateUsername(uid: String, username: String) {
        users.document(uid).update("username", username).await()
    }
}
