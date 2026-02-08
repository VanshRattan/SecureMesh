# Week 2 - Basic Internet Chat App

## Objective

Build a simple Firebase-based Android chat app for one-to-one text messaging.  
This is a **foundation phase** – no BLE, no mesh, no AI.

## What's Included

### Authentication
- **LoginActivity** – Email + password login
- **SignupActivity** – Create account, saves to Firestore `users/{userId}/email`
- **StartChatActivity** – Enter other user's email to start chat

### Chat
- **ChatActivity** – Real-time messaging with Firestore listeners
- **MessageAdapter** – RecyclerView adapter; sender (right) vs receiver (left) bubbles
- Text input + Send button

### Firestore Schema
```
users/
  userId/
    email

chats/
  chatId/   (userId1_userId2, sorted)
    messages/
      messageId/
        senderId
        text
        timestamp
```

## How to Run

1. **Firebase Setup** – Follow [FIREBASE_SETUP.md](FIREBASE_SETUP.md)
2. **Place `google-services.json`** in `app/` folder
3. Open project in **Android Studio**
4. Sync Gradle, build, run on two phones
5. Sign up User A on Phone 1, User B on Phone 2
6. Start chat by entering the other user's email

## Tech Stack

- Kotlin
- Firebase Auth (Email/Password)
- Firebase Firestore
- XML layouts (no Jetpack Compose)
- ViewBinding

## File Structure

```
app/src/main/
├── java/com/capstone/chatapp/
│   ├── ChatApp.kt              # Application class
│   ├── auth/
│   │   ├── LoginActivity.kt
│   │   ├── SignupActivity.kt
│   │   └── StartChatActivity.kt
│   ├── chat/
│   │   ├── ChatActivity.kt
│   │   └── MessageAdapter.kt
│   └── model/
│       └── Message.kt
├── res/
│   ├── layout/
│   │   ├── activity_login.xml
│   │   ├── activity_signup.xml
│   │   ├── activity_start_chat.xml
│   │   ├── activity_chat.xml
│   │   ├── item_message_sent.xml
│   │   └── item_message_received.xml
│   └── drawable/
│       ├── bubble_sent.xml
│       └── bubble_received.xml
└── AndroidManifest.xml
```

## Out of Scope (Week 2)

- No BLE
- No mesh networking
- No AI
- No encryption
- No delivery status
