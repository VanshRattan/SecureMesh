# Session Context — Capstone Emergency Chat App

> Snapshot of the work done across sessions. Read this first to pick up where we left off.
> Last updated: 2026-08-20

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

### Session 4 (2026-08-20) — transport-abstraction layer + unified packet format
Branch `feat/transport-abstraction`. Goal: stop higher layers from talking to a bearer (BLE GATT,
Firestore) directly, so a third tier (Wi-Fi Direct) and, later, end-to-end encryption slot in without
touching repositories or ViewModels. Purely a refactor — **no UI-observable behavior changed**, and
`:app:assembleDebug` succeeds (verified with a throwaway placeholder `google-services.json`, deleted
after — see §8, still missing for real).

**New — `data/transport/Transport.kt`.** `interface Transport { suspend fun sendToNextHop(packet,
nextHop, tier): SendResult; val incoming: Flow<Packet>; fun isAvailable(): Boolean }` plus
`enum SendResult { SENT, QUEUED, FAILED, UNAVAILABLE }`. Every bearer implements this; nothing above
it is allowed to know whether a packet went out over Bluetooth or the internet.

**New — `data/transport/Tier.kt`.** `enum Tier { INTERNET, WIFI_DIRECT, BLE_MESH }` — the three tiers
from the SafeSphere paper's target end-state (see `CLAUDE.md` §5). Only two are implemented; Wi-Fi
Direct is a stub (below).

**New — `data/transport/Packet.kt`.** The one wire format every bearer ultimately carries: a cleartext
header (`version`, `msgId`, `destId` nullable = broadcast, `srcId` nullable, `ttl`, `priority` enum
`NORMAL`/`EMERGENCY`, `tierTag`, `nonce`) plus an opaque `payload: ByteArray`. `serialize()`/
`deserialize()` use a compact `DataOutputStream`/`DataInputStream` binary framing (length-prefixed
strings + byte arrays), not the old manual `'|'`-join, so it round-trips arbitrary bytes safely. The
header is deliberately plaintext-forever; `payload` is plaintext today and becomes ciphertext once
E2E encryption lands (Session 3 of the original plan) — that's the whole point of the split.

**`BlePacket` migrated to wrap/produce `Packet`** (`data/transport/ble/BlePacket.kt`). Public API
(fields, `toBytes()`, `fromBytes()`, `relayed()`) is **byte-for-byte unchanged** for every existing
caller (`BleMeshManager`, `EmergencyViewModel`, `EmergencyRepository`) — only the internals moved:
`msgId`/`ttl`/`senderId` now live in `Packet`'s header (`srcId`), and `senderName`/`timestamp`/
`hopCount`/`reachCount`/`text` are packed into `Packet.payload` (still `'|'`-joined, still
human-debuggable in logcat). `toBytes()` = `toPacket().serialize()`; `fromBytes()` = `Packet
.deserialize()` + `fromPacket()`. **`BleMeshManager` needed zero changes** — it only ever called
`packet.toBytes()` / `BlePacket.fromBytes()`, which still exist with the same signatures.

**New — `BleTransport` (`data/transport/ble/BleTransport.kt`).** Wraps `BleMeshManager`. The mesh is a
flood, not point-to-point, so `nextHop` is ignored — `sendToNextHop` always broadcasts via
`mesh.send()`. `incoming` = `mesh.incoming.map { it.toPacket() }`. `isAvailable()` = `mesh.status.value
.running`.

**New — `InternetTransport` (`data/transport/InternetTransport.kt`).** Wraps Firestore directly (own
`firestore` instance, not a wrapper around the repositories — avoids a repository ↔ transport
circular dependency). `sendToNextHop`: `destId == null` → writes the `emergencies` doc (unwraps the
packet back to a `BlePacket` for the `senderId`/`senderName`/`text` fields, unchanged shape);
`destId != null` → writes to `chats/{chatId}/messages` where `chatId` is re-derived from
`srcId`/`destId` via the existing `Message.getChatId` (deterministic, both sides already agree on it).
`incoming` only carries **broadcast** SOS traffic (mirrors what `EmergencyRepository.observeEmergencies`
already exposed) — targeted 1:1 messages stay observed per-chat by `ChatRepository.observeMessages`,
since a global `Flow<Packet>` has no natural way to represent "all chats a screen isn't currently
open on." **Firestore document shapes are unchanged** — same fields, same collections, so no new
rules/index needed beyond what §7 already documents.

**New — `WifiDirectTransport` (`data/transport/WifiDirectTransport.kt`).** Stub: `isAvailable() =
false`, `sendToNextHop` always returns `UNAVAILABLE`, `incoming = emptyFlow()`. `TODO(Session 5)`.
Registered in `AppContainer` now so a future per-hop arbiter can already be written against the full
`Tier` set.

**`EmergencyRepository` and `ChatRepository` now build a `Packet` and call `InternetTransport`**
instead of writing to Firestore directly for sends (reads/`observe*` are unchanged — those still query
Firestore directly, since that's a repository-level concern, not a "send"). Both constructors now take
`InternetTransport` as a parameter. `ChatRepository.sendMessage` additionally still does the
`chats/{chatId}` summary upsert (denormalized names for the Home list) as a **direct** Firestore write
after the transport call succeeds — that's index bookkeeping for the UI, not part of the wire packet,
so it deliberately stays outside the `Transport` abstraction.

**`AppContainer` wiring:** `internetTransport`, `bleTransport`, `wifiDirectTransport` constructed
before the repositories; `chatRepository`/`emergencyRepository` now take `internetTransport` as a
constructor arg. `EmergencyViewModel`/`ChatViewModel` call sites are **untouched** — same method
signatures on both repositories.

**Not done / left for later:**
- `ChatRepository`/`EmergencyViewModel` still call `BleMeshManager`/`Firestore`-backed repositories
  directly rather than picking a tier through a real arbiter — there's no "auto-switch" logic living
  above the transports yet, just two independent send calls (BLE + internet) exactly as before. Building
  the actual per-hop arbiter (availability/congestion/energy/priority-driven) is future work, now that
  `Tier`/`Transport`/`Packet` exist for it to be built against.
- No offline 1:1 over BLE — `ChatRepository` only ever calls `InternetTransport`; nothing calls
  `BleTransport` for a targeted (non-broadcast) packet yet.
- `nonce` is always `ByteArray(0)` — no encryption yet, so there's nothing to carry an IV/nonce for.
- Not run on a device this session (pure refactor, verified by `:app:assembleDebug` only).

### Session 3 (2026-08-20) — BLE runtime instrumentation + real-device bug fixes
Branch `feat/ble-runtime-fixes`. Goal: make the offline SOS broadcast actually work phone-to-phone,
and make every failure visible in logcat instead of silent. **Still no on-device run** — everything
below is code-verified and compiles (`:app:assembleDebug` succeeds); see §8 for what needs two phones.

**New — structured logging (`data/transport/ble/BleLog.kt`).** One tag, `SafeSphereBLE`, and one line
format (`STEP | key=value …`) covering every stage: mesh start/precheck, advertise start/ok/fail/restart,
GATT server open/ready/fail/connect, scan start/result/fail, neighbour add/drop, identity request/serve/
read, TX connect/MTU/write/ok/fail, RX packet, dedup drop, relay send with the TTL decrement, TTL drop,
outbox replay, Bluetooth state changes, and foreground-service lifecycle. GATT/advertise/scan error codes
are decoded to names (e.g. `GATT_ERROR(133)`, `SCANNING_TOO_FREQUENTLY`). MACs are truncated to the last
5 chars and msgIds to the first 8 so a message can be followed across both phones.

**Permission + BLE-enable flow (the fixes most likely to have blocked a real device).**
- `BlePermissions` now splits `essential()` (SCAN/ADVERTISE/CONNECT on 12+, FINE_LOCATION below) from
  `optional()` (POST_NOTIFICATIONS on 13+). **Previously a denied notification permission stopped the
  mesh entirely** on Android 13/14, because the screen required `result.values.all { it }`.
- Added `isLocationServiceOn()`: on Android ≤ 11 BLE scans return **nothing** with the location toggle
  off, with no error anywhere. Now detected up front and prompted for.
- `EmergencyScreen` resolves one blocker at a time (`MeshBlocker`): permissions → Bluetooth enable →
  location settings, each with its own system prompt, re-checked on every `ON_RESUME`, and guarded so a
  declined prompt does not re-open forever. **There was previously no way to turn Bluetooth on** — the
  app only showed a snackbar.
- `EmergencyViewModel` exposes the blocker + advertising/scanning flags; the banner now says what is
  actually wrong instead of "Starting Bluetooth mesh…" forever.

**GATT client / advertiser / scanner fixes (by inspection).**
- **Concurrency:** `neighbors`, `nearby`, `seen`, `identityRequested` were plain `LinkedHashMap`/`Set`
  mutated from binder threads *and* the coroutine worker — a flood could throw
  `ConcurrentModificationException`. Now `ConcurrentHashMap` / lock-guarded.
- **Duplicate workers:** `start()` launched a new outbound consumer every call, so a stop→start cycle
  left two consumers racing on the same channel. The worker is now a tracked `Job`, and `stop()` drains
  the queues.
- **Write priority:** writes and identity reads share one serialized worker, but writes now win, so an
  SOS is never stuck behind a Nearby lookup (which could burn a full GATT timeout).
- **Stale neighbours:** never evicted before, so every broadcast burned a full timeout per phone that
  had walked away — serialized, that is minutes of delay for a real SOS. Now swept after 60 s, plus
  eviction after 3 consecutive write failures.
- **Timeouts** cut 8 s → 6 s, with a 200 ms settle between GATT ops (back-to-back connects yield 133).
- **Silent failure paths closed:** GATT `status` is now checked in every callback (it was ignored in
  `onConnectionStateChange`/`onServicesDiscovered`); a rejected `requestMtu` falls through to discovery
  instead of hanging; `writeCharacteristic`'s return value is checked (the API 33+ overload returns an
  int status, which was being discarded); a null `connectGatt` is handled.
- **Long writes:** the GATT server ignored `preparedWrite`, so any message larger than the negotiated
  MTU was silently dropped. Now buffered and committed in `onExecuteWrite`.
- **Advertiser:** failures were logged and forgotten. Now retried with backoff for recoverable errors,
  treated as success for `ALREADY_STARTED`, and reported clearly (not retried) for `FEATURE_UNSUPPORTED`
  — the case where a phone simply cannot be discovered.
- **Scanner:** `onScanFailed` was log-only. Now restarts with backoff, and waits 35 s past Android's
  5-starts-per-30-s throttle. Added `MATCH_MODE_AGGRESSIVE` and batch-result handling.
- **Bluetooth off/on:** toggling Bluetooth left the mesh a zombie (`running=true`, no radio). A
  `BluetoothAdapter.ACTION_STATE_CHANGED` receiver now tears down and brings the radio back.
- **`MeshStatus`** gained `advertising` / `scanning` / `serverReady` / `lastError`, and `running` is no
  longer set to true when nothing actually started.

**Delivery fixes.**
- **Outbox (new):** an SOS used to reach only neighbours already discovered when the button was
  pressed — the most likely reason a two-phone demo "does nothing". Broadcasts are now held for 90 s and
  pushed to each phone as it is discovered (`OUTBOX | replayed=N`).
- `incoming` is now `replay = 32`, so opening Emergency Mode shows SOSes that arrived while the mesh was
  running in the foreground service with no UI attached. The UI de-dups by `msgId`.

**Foreground service.**
- `START_STICKY` redelivers a **null** intent after a process kill, so the mesh used to restart
  advertising a blank uid. Identity is now cached in SharedPreferences.
- `startForeground` is wrapped: Android 14 throws `SecurityException` for a `connectedDevice` service
  without Bluetooth permissions granted, and Android 12+ throws when started from the background. Both
  are logged and the service stops cleanly instead of crashing mid-emergency.
- Added a content intent so tapping the notification reopens the app.

**Docs.** New `docs/RUNTIME_TEST.md`: exact two-phone procedure (build → install → sign up → start mesh →
go offline → send SOS → confirm receipt), the expected logcat trace at each step, a table explaining every
log step, and troubleshooting keyed to specific log lines.

**Manifest audit result: no changes needed.** `BLUETOOTH_SCAN` (with `neverForLocation`), `ADVERTISE`,
`CONNECT`, legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`/`ACCESS_FINE_LOCATION` capped at API 30,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, and
`android:foregroundServiceType="connectedDevice"` on the service were all already correct for 12–14.

**MainActivity audit result: it has no BLE permission handling at all** (contrary to the task's
assumption). It only applies the theme and hosts the NavHost. Left that way deliberately — asking for
Bluetooth at app launch, on the login screen, is worse UX than asking in Emergency Mode where the
permission is actually used. All permission handling lives in `EmergencyScreen` + `EmergencyViewModel`.

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
      Tier.kt                 # enum INTERNET / WIFI_DIRECT / BLE_MESH
      Packet.kt               # transport-agnostic wire format: cleartext header + opaque payload
      Transport.kt            # interface every bearer implements + SendResult enum
      InternetTransport.kt    # Transport over Firestore (emergencies + chats/{chatId}/messages)
      WifiDirectTransport.kt  # TODO(Session 5) stub — always UNAVAILABLE
      NetworkMonitor.kt        # validated-internet flow (online/offline)
      ble/
        BleConstants.kt        # UUIDs, TTL=10, REACH_CAP=100, MTU=185, timeouts/eviction/outbox windows
        BleLog.kt              # single logcat tag "SafeSphereBLE"; step vocabulary + error-code decoding
        BlePacket.kt           # SOS fields; wraps/produces Packet (toPacket/fromPacket) + relayed()
        BleTransport.kt        # Transport wrapping BleMeshManager (flood broadcast, nextHop ignored)
        NearbyPeer.kt          # a person discovered nearby (uid, name, address, lastSeen)
        BleMeshManager.kt      # advertiser + GATT server + scanner + GATT client + flood relay
                               #   + identity read → nearbyPeers + outbox + neighbour eviction
                               #   + BT-state receiver; all GATT client work serialized (writes first)
        BleMeshService.kt      # foreground service (type connectedDevice) keeping mesh alive
        BlePermissions.kt      # runtime perms per SDK level; essential vs optional; location-toggle check
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

### Blocks building right now
- **`app/google-services.json` is MISSING from the repo.** It is gitignored, and the file this doc
  previously claimed was "present in `app/`" is not there. `:app:assembleDebug` fails with
  `File google-services.json is missing` until you download it from the Firebase console. (A throwaway
  placeholder was used to verify compilation this session and then deleted — do not ship a fake one, it
  builds fine but every Firebase call fails at runtime, including sign-up.)
- **`./gradlew` wrapper** still missing. Use Android Studio or the cached Gradle 8.5 (see §6 and
  `docs/RUNTIME_TEST.md` §0.2).
- **Firestore rules + composite index** must be published/created in the console (see §7).

### Needs on-device confirmation (2 phones) — Session 3 changes are all inspection-only
Everything in the Session 3 changelog compiles and `:app:assembleDebug` succeeds, but **not one line of
it has run on a phone.** Follow `docs/RUNTIME_TEST.md` and confirm, in order:

1. **Mesh comes up:** `MESH_START | running=true`, `ADV_OK`, `GATT_SERVER_READY`, `SCAN_START` on both
   phones, and the "Emergency mesh active" notification appears.
2. **The permission flow does not dead-end** on Android 12, 13 and 14 — especially that *declining
   notifications on 13+ still starts the mesh* (the specific bug fixed this session).
3. **The Bluetooth-enable prompt appears** when Bluetooth is off, and the mesh starts after accepting.
4. **Discovery:** `NEIGHBOR_ADD` then `IDENTITY_OK | name=<the other user>` on both phones, and the
   Emergency chip reads "· 1 near".
5. **The actual goal — offline SOS delivery:** both phones offline, `TX_OK` on the sender and
   `RX_PACKET` + a red bubble on the receiver.
6. **The outbox path:** press SOS with `neighbors=0`, then bring the second phone into range, and confirm
   `OUTBOX | replayed=1` followed by delivery. This is new and unproven.
7. **Long writes** (`preparedWrite`): send an SOS longer than ~150 characters and confirm it arrives
   intact rather than as `RX_BAD`.
8. **Bluetooth toggle recovery:** turn Bluetooth off and on, confirm `BT_STATE | state=ON` and that
   delivery still works afterwards.
9. **Relay + dedup** need a **third** phone: `RELAY_SEND | ttl=10->9`, `RX_PACKET | hops=1`, and
   `DEDUP_DROP` for the second copy.
10. **Background survival:** screen off / app backgrounded, and after a process kill (the
    SharedPreferences identity fallback in `BleMeshService`).

### Known gaps not addressed this session
- **Discover → Nearby never starts the mesh itself.** If the user opens Discover before ever opening
  Emergency Mode, the Nearby tab is silently empty with no explanation. Emergency Mode is the only place
  that requests permissions and starts the service.
- **No offline 1:1** — tapping a Nearby/Discover person opens the *online* Firestore chat.
- **Encryption** not implemented (deferred by design; SOS is a public broadcast).
- **Battery duty-cycling** — the scanner runs in `SCAN_MODE_LOW_LATENCY` continuously, which is heavy.
- **Simultaneous-GATT-connection limit (~4–7)** is sidestepped by serializing to one connection at a
  time, which is safe but slow with many neighbours; no parallelism or connection pooling.
- **Message chunking above the MTU** is now handled by Android's long-write path (buffered server-side),
  but there is no application-level fragmentation for genuinely large payloads.

**Recommended next step:** drop in `google-services.json`, build, then run `docs/RUNTIME_TEST.md`
end to end on two phones and bring back the `SafeSphereBLE` logcat from both.

---

## 9. Key reference files

- Plan: `/Users/rishibhardwaj/.claude/plans/federated-swinging-bachman.md`
- **Two-phone runtime test + logcat guide: `docs/RUNTIME_TEST.md`** (start here for any BLE work)
- Existing docs: `README.md`, `WEEK2_README.md`, `FIREBASE_SETUP.md`
- Persistent memory (loaded each session): `.claude/.../memory/MEMORY.md`
  (no-tests preference, build workaround, encryption-deferred rationale)
