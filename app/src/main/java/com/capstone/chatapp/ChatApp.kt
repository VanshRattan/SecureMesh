package com.capstone.chatapp

import android.app.Application
import com.google.firebase.FirebaseApp

/**
 * Application class - initializes Firebase.
 * Firebase auto-initializes, but we keep this for any future setup.
 */
class ChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
