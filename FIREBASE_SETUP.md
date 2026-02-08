# Firebase Setup Instructions - Week 2 Chat App

Follow these steps to get the app running on two phones.

---

## 1. Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com/)
2. Click **Add project** (or use existing)
3. Name it (e.g. "Capstone Chat")
4. Disable Google Analytics (optional for learning)
5. Create project

---

## 2. Add Android App to Firebase

1. In Firebase Console, click the **Android icon** to add an Android app
2. **Android package name**: `com.capstone.chatapp`
3. **App nickname**: Capstone Chat (optional)
4. **Debug signing certificate SHA-1** (optional for Firestore, needed for Auth):
   - In Android Studio: `Build` → `Generate Signed Bundle/APK` → create keystore
   - Or run: `gradlew signingReport` in project root
   - Copy SHA-1 and add in Firebase
5. Click **Register app**
6. Download **google-services.json** and place it in:
   ```
   d:\Capstone_Project\app\google-services.json
   ```
7. Skip the rest of the wizard (Gradle plugin already added)

---

## 3. Enable Authentication

1. In Firebase Console → **Build** → **Authentication**
2. Click **Get started**
3. Go to **Sign-in method** tab
4. Enable **Email/Password** provider

---

## 4. Create Firestore Database

1. In Firebase Console → **Build** → **Firestore Database**
2. Click **Create database**
3. Choose **Start in test mode** (for learning; lock rules later)
4. Select a location (e.g. `us-central1`)

---

## 5. Firestore Security Rules (Test Mode)

For Week 2 learning, use these simple rules. **Replace with proper rules before production.**

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Users - anyone can read, only owner can write
    match /users/{userId} {
      allow read: if true;
      allow write: if request.auth != null && request.auth.uid == userId;
    }
    // Chats - only participants can read/write
    match /chats/{chatId} {
      match /messages/{messageId} {
        allow read, write: if request.auth != null;
      }
    }
  }
}
```

---

## 6. Firestore Index (if needed)

When you first send a message, Firestore may show an error with a link to create an index. Click that link - it will auto-create the required index for `messages` ordered by `timestamp`.

Or manually:
- Collection: `chats/{chatId}/messages`
- Fields: `timestamp` (Ascending)

---

## 7. Run on Two Phones
---

## 8. Firestore Structure Reference

```
users/
  {userId}/
    email: "user@example.com"

chats/
  {chatId}/   (e.g. "userId1_userId2" - sorted)
    messages/
      {messageId}/
        senderId: "userId"
        text: "Hello!"
        timestamp: Timestamp
```

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| `google-services.json` not found | Place file in `app/` folder, rebuild |
| Auth fails | Check Email/Password is enabled in Firebase Auth |
| Messages not loading | Check Firestore is created, rules allow read |
| Index error | Click the link in the error to auto-create index |
| User not found | Other user must sign up first on their device |

---

## Next Steps (Future Weeks)

- Add Firestore security rules (restrict by chat participants)
- Add delivery status (not in Week 2 scope)
- Add BLE/mesh (Weeks 3–4)
