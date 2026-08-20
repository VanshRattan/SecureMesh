Let me check the outputs folder and recreate it.It's actually there — `CLAUDE.md` (5,683 bytes) is sitting in your outputs folder. The problem is almost certainly that a file literally named `CLAUDE.md` is easy to miss (and the preview cards have been blocked this whole session). Let me make it unmistakable by saving a second copy with an obvious name, and try to surface both.The preview card is blocked by the environment, so here's the surest fix: both files exist in your outputs folder, and I'm pasting the **full contents below** so you can just copy it straight into a new `CLAUDE.md` if you can't find the file.

Both files are here:
- `CLAUDE.md`
- `CLAUDE_md__rename_to_CLAUDE.md__and_put_in_repo_root.txt` (identical copy with an obvious name)

in your outputs folder: `...\local_...\outputs\`

To use it: create a file named `CLAUDE.md` in your repo root and paste this in:

```markdown
# CLAUDE.md — SafeSphere / Capstone Emergency Communication App

> Drop this file in the **repository root** (rename to `CLAUDE.md`). Claude Code loads it automatically
> every session, so it is the standing context for all work. Read `SESSION_CONTEXT.md` at the start of
> every session and **update it at the end** of every session.

---

## 1. What this project is

An Android app for **secure emergency communication that works with no infrastructure**. It began as a
Firebase 1:1 chat and now also has an offline **Bluetooth Low Energy (BLE) mesh** for broadcasting SOS
messages phone-to-phone. The end goal (the research paper this backs, "SafeSphere") is a
**transport-agnostic, relay-blind messenger** that automatically switches across **three transports**
and keeps every relay unable to read what it forwards.

- Package: `com.capstone.chatapp`
- Platform: Android only — Kotlin, Jetpack Compose, MVVM, single Activity + NavHost
- DI: **manual** via `di/AppContainer.kt` (no Hilt)
- Backend (Internet tier): Firebase Auth + Cloud Firestore (no custom server)
- Offline transport: BLE mesh (advertiser + GATT server/client + flood relay + foreground service)

## 2. Current architecture (package layout)

```
com.capstone.chatapp/
  MainActivity.kt              # single Activity; applies theme; hosts NavHost
  ChatApp.kt                   # Application; builds AppContainer
  di/AppContainer.kt           # repositories + BleMeshManager + NetworkMonitor (singletons)
  navigation/                  # Routes.kt, AppNavHost.kt
  ui/
    theme/  components/  util/TimeFormat.kt
    login/  signup/  home/  discover/  chat/  profile/  emergency/   # Screen + ViewModel each
  data/
    model/     Message.kt, User.kt, ChatSummary.kt
    local/     EmergencyHistoryStore.kt          # DataStore-persisted SOS history
    repository/ AuthRepository, UserRepository, ChatRepository,
                EmergencyRepository, SettingsRepository
    transport/
      NetworkMonitor.kt                          # validated-internet online/offline flow
      ble/
        BleConstants.kt   # UUIDs, TTL=10, REACH_CAP=100, MTU=185
        BlePacket.kt      # wire format + serialize/deserialize + relayed()
        NearbyPeer.kt     # uid, name, address, lastSeen
        BleMeshManager.kt # advertiser + GATT server + scanner + GATT client + flood relay + identity read
        BleMeshService.kt # foreground service keeping mesh alive
        BlePermissions.kt # runtime perms per SDK level
```

**Patterns to preserve:** one ViewModel per screen (state as `StateFlow`, survives rotation);
`rememberSaveable` for transient input; repositories hide all Firebase; manual DI via `AppContainer`.

## 3. Toolchain & build

AGP 8.2.0, Kotlin 1.9.20, Compose compiler 1.5.4, Compose BOM 2024.02.02, Firebase BOM 32.7.0.
`google-services.json` is present in `app/`. Firestore rules + a composite index on `chats`
(`participants` array + `lastTimestamp` desc) must exist in the Firebase console.

Build (a `gradlew` wrapper should be generated in Session 0; until then use a cached Gradle 8.5):
```
./gradlew :app:compileDebugKotlin   # fast error check
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

## 4. Locked decisions & constraints (respect these)

- **Do NOT add unit tests.** The team does not want them. Use **on-device manual verification** and
  logcat instrumentation instead — that is different from unit testing and IS required.
- Keep the app **compiling after every change** (`:app:assembleDebug` must succeed).
- Preserve the **Compose + MVVM + manual-DI** architecture; do not introduce Hilt or rewrite screens
  wholesale unless a task explicitly says so.
- Emergency SOS is a **public broadcast** to everyone reachable; encryption applies to **targeted 1:1**
  messages, not the broadcast.
- Work on a **`feat/<topic>` branch**, never commit directly to `main`. Small, reviewable commits.

## 5. Target end-state (align the app to the SafeSphere paper)

1. **Three-tier transport, auto-switched:** Internet (Firestore/relay) → Wi-Fi Direct → BLE mesh.
2. **End-to-end encryption with relay-node blindness:** X25519 Diffie–Hellman + AES-256-GCM; keys in
   Android Keystore; **every relay (including Firestore) forwards ciphertext it cannot decrypt.**
3. **Per-hop transport arbiter** driven by availability, congestion, energy, and message priority,
   with seamless handover and a store-carry-forward queue for intermittent links.
4. **Offline 1:1 messaging over BLE** (not just broadcast).
5. **Evaluation instrumentation** so we can measure delivery ratio, latency, hop count, per-tier usage,
   and battery — these numbers become the paper's results section.

## 6. Known gaps / risks (as of last snapshot)

- BLE mesh and "nearby-by-name" are **code-only, never run on a device** (top risk).
- **No encryption anywhere**; Firestore stores plaintext.
- **No Wi-Fi Direct tier**; switching logic is trivial (online→both, offline→BLE).
- **No offline 1:1**; tapping a nearby person opens the online chat.
- Mesh hardening missing: GATT simultaneous-connection cap (~4–7), reconnect/retry, large-message
  chunking, stale nearby-peer eviction, seen-set eviction, battery duty-cycling.
- No `./gradlew` wrapper; Firestore rules/index must be published in console.

## 7. Session workflow (every time)

1. **Read `SESSION_CONTEXT.md` first**, then the files the task names.
2. Confirm scope in one short plan before editing.
3. Implement in small steps; keep it compiling.
4. **Update `SESSION_CONTEXT.md`** (what changed, what's verified vs not, next step) and commit on a
   `feat/*` branch with a clear message.
```

That's the complete file. If you paste it into a new `CLAUDE.md` in your repo root, you're set — no need to hunt for the generated file at all.