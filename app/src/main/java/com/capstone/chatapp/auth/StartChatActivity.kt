package com.capstone.chatapp.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.capstone.chatapp.R
import com.capstone.chatapp.chat.ChatActivity
import com.capstone.chatapp.databinding.ActivityStartChatBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Start Chat screen - user enters the email of the person to chat with.
 * We look up their userId in Firestore, then open ChatActivity with that userId.
 */
class StartChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStartChatBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStartChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        // If not logged in, go back to login
        if (auth.currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding.btnLogout.setOnClickListener {
            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.btnStartChat.setOnClickListener { startChatWithUser() }
    }

    private fun startChatWithUser() {
        val email = binding.etChatEmail.text.toString().trim()

        if (email.isEmpty()) {
            binding.etChatEmail.error = getString(R.string.error_empty_chat_email)
            return
        }

        // Don't allow chatting with self
        if (email.equals(auth.currentUser!!.email, ignoreCase = true)) {
            Toast.makeText(this, "You cannot chat with yourself", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnStartChat.isEnabled = false

        // Look up user by email in Firestore
        firestore.collection("users")
            .whereEqualTo("email", email)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                binding.btnStartChat.isEnabled = true
                if (snapshot.isEmpty) {
                    Toast.makeText(
                        this,
                        "No user found with that email. They must sign up first.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@addOnSuccessListener
                }
                val otherUserId = snapshot.documents[0].id
                openChat(otherUserId)
            }
            .addOnFailureListener {
                binding.btnStartChat.isEnabled = true
                Toast.makeText(this, "Error finding user", Toast.LENGTH_SHORT).show()
            }
    }

    private fun openChat(otherUserId: String) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra(ChatActivity.EXTRA_OTHER_USER_ID, otherUserId)
        startActivity(intent)
    }
}
