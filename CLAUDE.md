# CLAUDE.md — SafeSphere / Capstone Emergency Communication App

> Claude Code loads this file automatically every session, so it is the standing context for
> all work. Read `SESSION_CONTEXT.md` at the start of every session and **update it at the
> end** of every session.

---

## 1. What this project is

An Android app for **secure emergency communication that works with no infrastructure**. It began as a
Firebase 1:1 chat and now also has an offline **Bluetooth Low Energy (BLE) mesh** for broadcasting SOS
messages phone-to-phone, plus an emerging **Wi-Fi Direct** tier. The end goal (the research paper this
backs, "SafeSphere") is a **transport-agnostic, relay-blind messenger** that automatically switches
across **three transports** and keeps every relay unable to read what it forwards.

- Package: `com.capstone.chatapp`
- Platform: Android only — Kotlin, Jetpack Compose, MVVM, single Activity + NavHost
- DI: **manual** via `di/AppContainer.kt` (no Hilt)
- Backend (Internet tier): Firebase Auth + Cloud Firestore (no custom server)
- Offline transports: BLE mesh (advertiser + GATT server/client + flood relay + foreground service);
  Wi-Fi Direct (peer discovery + group formation + socket relay, single-group star topology)

## 2. Current architecture (package layout)

```
com.capstone.chatapp/
  MainActivity.kt              # single Activity; applies theme; hosts NavHost
  ChatApp.kt                   # Application; builds AppContainer
  di/AppContainer.kt           # repositories + BleMeshManager + WifiDirectManager + NetworkMonitor (singletons)
  navigation/                  # Routes.kt, AppNavHost.kt
  ui/
    theme/  components/  util/TimeFormat.kt
    login/  signup/  home/  discover/  chat/  profile/  emergency/  pairing/   # Screen + ViewModel each
  data/
    model/     Message.kt, User.kt, ChatSummary.kt
    local/     EmergencyHistoryStore.kt, ContactSecurityStore.kt   # DataStore-persisted state
    security/  CryptoManager.kt, SafetyNumber.kt, QrCodec.kt       # X25519 + AES-256-GCM E2E encryption
    repository/ AuthRepository, UserRepository, ChatRepository,
                EmergencyRepository, SettingsRepository
    transport/
      Tier.kt, Packet.kt, Transport.kt                # bearer-agnostic abstraction (Session 4)
      InternetTransport.kt                             # Transport over Firestore
      NetworkMonitor.kt                                # validated-internet online/offline flow
      ble/
        BleConstants.kt   BleChunk.kt   BleLog.kt   BlePacket.kt   BleTransport.kt
        NearbyPeer.kt   BleMeshManager.kt   BleMeshService.kt   BlePermissions.kt
      wifidirect/
        WifiDirectConstants.kt   WifiDirectLog.kt   WifiDirectPeer.kt   WifiDirectFrame.kt
        WifiDirectPermissions.kt   WifiDirectManager.kt   WifiDirectTransport.kt
```

**Patterns to preserve:** one ViewModel per screen (state as `StateFlow`, survives rotation);
`rememberSaveable` for transient input; repositories hide all Firebase; manual DI via `AppContainer`;
each bearer lives in its own `data/transport/<bearer>/` package with its own `*Log.kt` (structured
`STEP | key=value` logcat lines under one tag) and `*Permissions.kt`.

## 3. Toolchain & build

AGP 8.2.0, Kotlin 1.9.20, Compose compiler 1.5.4, Compose BOM 2024.02.02, Firebase BOM 32.7.0.
`google-services.json` is gitignored and must be supplied locally (download from the Firebase console).
Firestore rules + a composite index on `chats` (`participants` array + `lastTimestamp` desc) must exist
in the Firebase console — see `SESSION_CONTEXT.md` §7.

No `./gradlew` wrapper exists in this repo. Use the cached Gradle 8.5 + Android Studio's bundled JDK
(see `SESSION_CONTEXT.md` §6 or `docs/RUNTIME_TEST.md` §0.2 for the exact Windows/macOS commands):
```
gradle :app:compileDebugKotlin   # fast error check
gradle :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

## 4. Locked decisions & constraints (respect these)

- **Do NOT add unit tests.** The team does not want them. Use **on-device manual verification** and
  logcat instrumentation instead — that is different from unit testing and IS required.
- Keep the app **compiling after every change** (`:app:assembleDebug` must succeed).
- Preserve the **Compose + MVVM + manual-DI** architecture; do not introduce Hilt or rewrite screens
  wholesale unless a task explicitly says so.
- Emergency SOS is a **public broadcast** to everyone reachable; encryption applies to **targeted 1:1**
  messages, not the broadcast — an intentionally plaintext-forever broadcast, not a gap.
- Work on a **`feat/<topic>` branch**, never commit directly to `main`. Small, reviewable commits.

## 5. Target end-state (align the app to the SafeSphere paper)

1. **Three-tier transport, auto-switched:** Internet (Firestore/relay) → Wi-Fi Direct → BLE mesh.
2. **End-to-end encryption with relay-node blindness:** X25519 Diffie–Hellman + AES-256-GCM; keys in
   Android Keystore-wrapped storage; **every relay (including Firestore) forwards ciphertext it cannot
   decrypt.** Implemented for targeted 1:1 messages; the SOS broadcast stays plaintext by design.
3. **Per-hop transport arbiter** driven by availability, congestion, energy, and message priority,
   with seamless handover and a store-carry-forward queue for intermittent links. Not yet built — all
   three `Transport` implementations exist but nothing arbitrates between them yet.
4. **Offline 1:1 messaging over BLE / Wi-Fi Direct** (not just broadcast) — not yet built.
5. **Evaluation instrumentation** so we can measure delivery ratio, latency, hop count, per-tier usage,
   and battery — these numbers become the paper's results section.

## 6. Known gaps / risks (as of last snapshot — see `SESSION_CONTEXT.md` for the authoritative, dated list)

- BLE mesh and Wi-Fi Direct are **code-only, never run on a device** (top risk for both).
- **No per-hop arbiter** — BLE and Internet are both driven directly by ViewModels/repositories;
  Wi-Fi Direct's transport exists but nothing calls it yet (see `docs/WIFIDIRECT_TEST.md` §0.2/§3).
- **No offline 1:1** over BLE or Wi-Fi Direct — tapping a nearby person opens the online Firestore chat.
- Wi-Fi Direct is **single-group only** (a stock-Android platform constraint); multi-group multi-hop is
  documented but not implemented — see the class doc on `WifiDirectManager`.
- No `./gradlew` wrapper; Firestore rules/index must be published in console.

## 7. Session workflow (every time)

1. **Read `SESSION_CONTEXT.md` first**, then the files the task names.
2. Confirm scope in one short plan before editing.
3. Implement in small steps; keep it compiling.
4. **Update `SESSION_CONTEXT.md`** (what changed, what's verified vs not, next step) and commit on a
   `feat/*` branch with a clear message.
