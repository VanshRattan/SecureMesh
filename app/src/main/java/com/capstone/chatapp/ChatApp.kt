package com.capstone.chatapp

import android.app.Application
import com.capstone.chatapp.di.AppContainer
import com.google.firebase.FirebaseApp

/**
 * Application class - initializes Firebase and holds the app-wide DI container.
 */
class ChatApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        container = AppContainer(this)
    }
}
