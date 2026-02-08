package com.capstone.chatapp.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.capstone.chatapp.R
import com.capstone.chatapp.databinding.ActivitySignupBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Signup screen - creates new account with email and password.
 * On success, saves user to Firestore (users/{userId}/email) and goes to StartChatActivity.
 */
class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        binding.btnSignup.setOnClickListener { performSignup() }
        binding.tvLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun performSignup() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()

        if (email.isEmpty()) {
            binding.etEmail.error = getString(R.string.error_empty_email)
            return
        }
        if (password.isEmpty()) {
            binding.etPassword.error = getString(R.string.error_empty_password)
            return
        }
        if (password.length < 6) {
            binding.etPassword.error = "Password must be at least 6 characters"
            return
        }

        binding.btnSignup.isEnabled = false

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Save user to Firestore: users/{userId}/email
                    val userId = auth.currentUser!!.uid
                    firestore.collection("users").document(userId)
                        .set(mapOf("email" to email))
                        .addOnSuccessListener {
                            goToStartChat()
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "User created but sync failed", Toast.LENGTH_SHORT).show()
                            goToStartChat()
                        }
                } else {
                    binding.btnSignup.isEnabled = true
                    Toast.makeText(
                        this,
                        task.exception?.message ?: getString(R.string.error_auth_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun goToStartChat() {
        startActivity(Intent(this, StartChatActivity::class.java))
        finish()
    }
}
