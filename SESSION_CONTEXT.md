# Session Context — Capstone Emergency Chat App

> Snapshot of the work done across sessions. Read this first to pick up where we left off.
> Last updated: 2026-08-07

---

## 1. What this project is

An Android app that started as a **Firebase internet-only 1:1 chat** and was rebuilt this session
into a **modern Compose app + an offline emergency messenger** that works with **no internet** by
broadcasting SOS messages across nearby phones over a **Bluetooth Low Energy (BLE) mesh**.

- Package: `com.capstone.chatapp`
- Platform: Android only (Kotlin, Jetpack Compose, MVVM)
- Backend: Firebase Auth + Cloud Firestore (managed — no custom server)
- Offline transport: BLE mesh (phone-to-phone, no infrastructure)

The big-picture goal (from `README.md`): a secure emergency communication system with BLE mesh +
automatic internet↔BLE switching.

---

## 2. Where we started vs. where we are

**Start of session:** XML layouts + 4 Activities + ViewBinding, Firebase logic inside Activities,
7 Kotlin files (~518 lines), no BLE, no ViewModels, no theming. Ambitious README, tiny app.

**Now:** Full **Jetpack Compose + MVVM** rewrite with a repository layer, DataStore theming, and a
complete **BLE emergency mesh**. Old XML/Activities deleted. **The app compiles and builds a 20 MB
debug APK** (`app/build/outputs/apk/debug/app-debug.apk`).

---

## 2b. Changelog

### Session 1 (2026-08-04) — the big rewrite
XML/Activities → Compose + MVVM; repository layer; DataStore theming; full BLE emergency mesh
(advertiser + GATT server/client + flood relay + foreground service); auth, 1:1 chat, SOS broadcast.

### Session 2 (2026-08-07) — messaging UX overhaul
Locked with the user: SOS stays a public broadcast but **restyled as a chat**; Discover lists **all
registered users** (online); Nearby shows people **by name** over BLE. Delivered in 4 phases:

- **Phase 1 — SOS as a chat + timestamps + saved history.**
  SOS is now left/right **bubbles** (mine right, others left), **newest at the bottom**, auto-scrolls,
  and shows a **timestamp** per message. Normal chat bubbles also show timestamps now
  (`ui/util/TimeFormat.kt`). SOS history **persists across restarts** via a new DataStore-backed
  `data/local/EmergencyHistoryStore.kt` (covers offline-only BLE messages Firestore never saw).
  Sending an SOS now **clears the input box** (chat already did).
- **Phase 2 — Home is a conversation list.**
  Home now lists **people you've chatted with** (avatar, name, last-message preview, time) → tap to
  reopen. SOS moved to a red FAB; Discover + Profile in the top bar. Backed by a new parent
  `chats/{chatId}` summary doc written on each send + `ChatRepository.observeRecentChats`
  + `data/model/ChatSummary.kt`. `sendMessage` now takes sender/peer names to stamp the summary.
- **Phase 3 — Discover (all users).**
  New `ui/discover/` screen (search icon on Home) with an **All Users** tab listing every account
  (client-side search); tap anyone to chat. New `UserRepository.listUsers(excludeUid)`.
- **Phase 4 — Nearby by name (BLE identity).** ⚠ untested (needs 2 phones)
  Discover's **Nearby** tab lists people physically near you over BLE. Added a readable **identity
  characteristic** (`uid|name`) to the GATT server; on scanning a device the client reads it and
  builds `NearbyPeer`, exposed as `BleMeshManager.nearbyPeers: StateFlow`. All GATT client work
  (writes + identity reads) is serialized to avoid overlapping connects. Tapping a Nearby/Discover
  person opens the **standard Firestore 1:1 chat** — true offline 1:1 BLE delivery is still out of scope.

**Build status after Session 2:** `:app:assembleDebug` succeeds (20 MB APK). Only runtime-tested
surfaces are unchanged from Session 1 (i.e. still nothing verified on a device — see §8).

**Firebase console changes now required (see §7):** the new `chats/{chatId}` parent doc needs a rule,
and the Home list query needs a composite index on `chats` (`participants` array + `lastTimestamp` desc).

---

## 3. Decisions locked with the user (important)

- **No tests** — the user does not want tests; do not flag missing tests as a gap.
- **Full system built**: BLE mesh + auto-switch, done in phases.
- **Emergency Mode = broadcast SOS** to *everyone reachable* (no single recipient), SOS-style.
- **When online, send over BOTH** internet (Firestore) and BLE; receivers de-dup by `msgId`.
- **Multi-hop flood**, **shortest path wins** (first arrival shown, later duplicates dropped),
  **reach cap ≈ 100 devices** (approximate — decentralized flood can't count globally; backed by a
  10-hop TTL).
- **UI: full Compose + MVVM**, replacing XML/Activities.
- **Theme: Light / Dark / Follow-System, persisted in DataStore.**
- **Encryption intentionally deferred** — an SOS is a public broadcast, so E2E-to-one-recipient is
  meaningless. If wanted later, it belongs on a *targeted 1:1 BLE message* variant (X25519 +
  Android Keystore), not the broadcast.

---

## 4. Architecture (current package layout)

```
com.capstone.chatapp/
  MainActivity.kt              # single Activity; applies persisted theme + hosts NavHost
  ChatApp.kt                   # Application; builds AppContainer (manual DI)
  di/AppContainer.kt           # holds all repositories + BleMeshManager + NetworkMonitor (singletons)
  navigation/
    Routes.kt                  # login, signup, home, discover, chat/{peerUid}, profile, emergency
    AppNavHost.kt              # NavHost wiring
  ui/
    theme/                     # Color.kt, Type.kt, Theme.kt (Material3 light+dark, ThemeMode enum)
    components/                # AppTextField, LoadingButton, AuthScaffold (shared UX)
    util/     TimeFormat.kt    # formatTime(Long) / formatTime(Timestamp) for message times
    login/    LoginScreen + LoginViewModel
    signup/   SignupScreen + SignupViewModel
    home/     HomeScreen + HomeViewModel        # conversation list (recent chats) + Discover/Profile/SOS entries
    discover/ DiscoverScreen + DiscoverViewModel  # All Users (Firestore) + Nearby (BLE) tabs
    chat/     ChatScreen + ChatViewModel        # realtime 1:1, theme-aware bubbles + timestamps
    profile/  ProfileScreen + ProfileViewModel  # username, theme selector, logout
    emergency/ EmergencyScreen + EmergencyViewModel  # SOS broadcast, chat-styled bubbles + persisted history
  data/
    model/        Message.kt, User.kt, ChatSummary.kt
    local/        EmergencyHistoryStore.kt      # DataStore-persisted SOS history (JSON)
    repository/    AuthRepository, UserRepository (listUsers), ChatRepository (observeMessages +
                   observeRecentChats + chat-summary upsert), EmergencyRepository (Firestore
                   `emergencies`), SettingsRepository (DataStore)
    transport/
      NetworkMonitor.kt        # validated-internet flow (online/offline)
      ble/
        BleConstants.kt        # Service/message/identity characteristic UUIDs, TTL=10, REACH_CAP=100, MTU=185
        BlePacket.kt           # wire format + serialize/deserialize + relayed()
        NearbyPeer.kt          # a person discovered nearby (uid, name, address, lastSeen)
        BleMeshManager.kt      # advertiser + GATT server + scanner + GATT client + flood relay
                               #   + identity characteristic read → nearbyPeers StateFlow
        BleMeshService.kt      # foreground service (type connectedDevice) keeping mesh alive
        BlePermissions.kt      # runtime perms per SDK level
```

**Patterns:** one `ViewModel` per screen (state as `StateFlow`, survives rotation);
`rememberSaveable` for transient input (chat draft, SOS draft); repositories hide all Firebase;
manual DI via `AppContainer` (no Hilt).

---

## 5. Feature checklist

**Messaging**
- [x] Firebase email/password auth (login/signup) via `AuthRepository`
- [x] Realtime 1:1 chat over Firestore (`ChatRepository.observeMessages` callbackFlow)
- [x] Home = conversation list of people you've chatted with (`observeRecentChats`, `chats/{chatId}` summary)
- [x] Discover directory: browse ALL registered users, tap to chat (`UserRepository.listUsers`)
- [x] Nearby-by-name over BLE (identity characteristic → `nearbyPeers`) — ⚠ code-only, needs 2 phones
- [x] Emergency SOS broadcast over BLE mesh (no internet needed)
- [x] SOS restyled as a chat (bubbles, newest-at-bottom, timestamps) + history persisted across restarts
- [x] Multi-hop relay (flood + seen-set), shortest-path-wins, ~100 reach cap + 10-hop TTL
- [x] Dual-send when online (BLE + Firestore `emergencies`), de-dup by `msgId`
- [x] Online / Offline·BLE status badge + nearby-device count
- [ ] Encryption — deferred by design (see decisions)

**UI / UX (all implemented)**
- [x] Full Compose + MVVM, single Activity + NavHost
- [x] Sent-right / received-left bubbles with per-message timestamps (chat AND SOS)
- [x] Input box clears after sending (chat AND SOS)
- [x] Survives config change (VM state + rememberSaveable)
- [x] Email field strips spaces + forces lowercase
- [x] Buttons never hidden behind keyboard (`imePadding` + scroll)
- [x] Loading spinner rendered *inside* the button (no overflow)
- [x] Font-scale / display-size safe (sp text, no fixed heights, scrollable)
- [x] Material3 Light/Dark theme; text-field cursor/indicators follow theme
- [x] Profile screen: change username, theme selector (Light/Dark/System, persisted), logout w/ confirm
- [x] Elder-friendly: large type scale, big buttons, high contrast

---

## 6. How to build (NO gradlew wrapper exists in this repo)

There is no `./gradlew` and no `gradle` on PATH. Use the cached Gradle 8.5 + Android Studio's JDK:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
GRADLE=$(echo ~/.gradle/wrapper/dists/gradle-8.5-bin/*/gradle-8.5/bin/gradle)
"$GRADLE" :app:compileDebugKotlin   # fast error check
"$GRADLE" :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

Or just open in Android Studio and Run. Toolchain: AGP 8.2.0, Kotlin 1.9.20, Compose compiler 1.5.4,
Compose BOM 2024.02.02, Firebase BOM 32.7.0. `google-services.json` is present in `app/`.

**TODO:** run `gradle wrapper` once to generate `./gradlew` so the standard command works.

---

## 7. Firebase setup required before running (no code deploy, just console config)

1. **Enable Email/Password** auth (Console → Authentication → Sign-in method).
2. **Create Firestore database** (Console → Firestore).
3. **Publish security rules** (Console → Firestore → Rules):

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid} {
      allow read: if request.auth != null;                 // read = Discover directory list
      allow write: if request.auth != null && request.auth.uid == uid;
    }
    // Parent chat doc = conversation summary (participants, lastMessage, lastTimestamp)
    // that powers the Home conversation list. NEW in Session 2 — required.
    match /chats/{chatId} {
      allow read, write: if request.auth != null;
      match /messages/{msgId} {
        allow read, write: if request.auth != null;
      }
    }
    match /emergencies/{msgId} {
      allow read, create: if request.auth != null;
    }
  }
}
```

4. **Composite index (NEW in Session 2)** — the Home list queries `chats` with
   `participants array-contains <uid>` ordered by `lastTimestamp` descending. Either click the
   auto-generated link Firestore logs on first Home load, or create it manually:
   collection `chats`, fields `participants` (Arrays) + `lastTimestamp` (Descending).

No app server, no hosting, no CI/CD needed for a demo.

---

## 8. What is NOT done / not verified (be honest about this)

- **BLE mesh never runtime-tested** — requires 2 physical Android phones (emulators can't BLE-advertise).
  Code compiles; behavior unconfirmed. This is the #1 remaining risk.
- **Nearby-by-name (Session 2) never runtime-tested** — the identity-characteristic read + `nearbyPeers`
  flow are code-only. Nearby also only populates once the mesh is running (open Emergency Mode once to
  grant BLE). Tapping Nearby/Discover opens the *online* Firestore chat, not an offline BLE thread.
- **Nothing runtime-tested** (no device/emulator was run either session). All screens are code-verified only.
- **Encryption** not implemented (deferred by design).
- **Firestore rules + composite index** must be published/created in the console (see §7).
- **Mesh hardening** not done: battery duty-cycling, reconnect/retry, large-message chunking,
  simultaneous-GATT-connection limits (~4–7), stale-nearby-peer eviction. Documented as known drawbacks.
- **`./gradlew` wrapper** missing.

**Recommended next step:** install on two phones → sign up two accounts → chat online → turn off
Wi-Fi/data → send an SOS from Emergency Mode → confirm it appears on the other phone. Bring back
logcat if anything fails.

---

## 9. Key reference files

- Plan: `/Users/rishibhardwaj/.claude/plans/federated-swinging-bachman.md`
- Existing docs: `README.md`, `WEEK2_README.md`, `FIREBASE_SETUP.md`
- Persistent memory (loaded each session): `.claude/.../memory/MEMORY.md`
  (no-tests preference, build workaround, encryption-deferred rationale)
