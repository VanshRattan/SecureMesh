package com.capstone.chatapp.chat

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.capstone.chatapp.R
import com.capstone.chatapp.databinding.ActivityChatBinding
import com.capstone.chatapp.model.Message
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp

/**
 * Chat screen - displays messages in real time using Firestore listeners.
 * Sender messages appear on the right, receiver on the left.
 *
 * Firestore structure: chats/{chatId}/messages/{messageId}
 *   - senderId
 *   - text
 *   - timestamp
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OTHER_USER_ID = "extra_other_user_id"
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var adapter: MessageAdapter
    private var listenerRegistration: ListenerRegistration? = null

    private var currentUserId: String = ""
    private var otherUserId: String = ""
    private var chatId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        currentUserId = auth.currentUser?.uid ?: ""
        otherUserId = intent.getStringExtra(EXTRA_OTHER_USER_ID) ?: ""

        if (currentUserId.isEmpty() || otherUserId.isEmpty()) {
            Toast.makeText(this, "Invalid chat", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        chatId = Message.getChatId(currentUserId, otherUserId)

        setupRecyclerView()
        setupListeners()
        setupFirestoreListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        listenerRegistration?.remove()
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter(currentUserId)
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true  // New messages appear at bottom
        }
        binding.rvMessages.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        // Get other user's email for title (optional - could show "Chat" or fetch email)
        firestore.collection("users").document(otherUserId).get()
            .addOnSuccessListener { doc ->
                binding.tvChatTitle.text = doc.getString("email") ?: "Chat"
            }

        binding.btnSend.setOnClickListener { sendMessage() }
    }

    /**
     * Real-time Firestore listener - updates RecyclerView when messages change.
     * Listens to: chats/{chatId}/messages ordered by timestamp.
     */
    private fun setupFirestoreListener() {
        val messagesRef = firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)

        listenerRegistration = messagesRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Toast.makeText(this, "Error loading messages", Toast.LENGTH_SHORT).show()
                return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener

            val messages = snapshot.documents.mapNotNull { doc ->
                val senderId = doc.getString("senderId") ?: return@mapNotNull null
                val text = doc.getString("text") ?: return@mapNotNull null
                val timestamp = doc.getTimestamp("timestamp")
                Message(senderId = senderId, text = text, timestamp = timestamp)
            }
            adapter.setMessages(messages)
            scrollToBottom()
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) {
            binding.etMessage.error = getString(R.string.error_empty_message)
            return
        }

        binding.etMessage.setText("")

        val messageData = hashMapOf(
            "senderId" to currentUserId,
            "text" to text,
            "timestamp" to Timestamp.now()
        )

        firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .add(messageData)
            .addOnSuccessListener {
                // Message sent - listener will update UI automatically
                scrollToBottom()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show()
            }
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) {
            binding.rvMessages.smoothScrollToPosition(count - 1)
        }
    }
}
