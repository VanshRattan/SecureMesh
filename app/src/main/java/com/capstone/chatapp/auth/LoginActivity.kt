package com.capstone.chatapp.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.capstone.chatapp.R
import com.capstone.chatapp.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth

/**
 * Login screen - allows user to sign in with email and password.
 * On success, navigates to StartChatActivity.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // If already logged in, go to chat
        if (auth.currentUser != null) {
            goToStartChat()
            return
        }

        binding.btnLogin.setOnClickListener { performLogin() }
        binding.tvSignup.setOnClickListener {
            startActivity(Intent(this, SignupActivity::class.java))
            finish()
        }
    }

    private fun performLogin() {
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

        binding.btnLogin.isEnabled = false

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                binding.btnLogin.isEnabled = true
                if (task.isSuccessful) {
                    goToStartChat()
                } else {
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
