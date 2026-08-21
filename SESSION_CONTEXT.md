# Session Context — Capstone Emergency Chat App

> Snapshot of the work done across sessions. Read this first to pick up where we left off.
> Last updated: 2026-08-21 (Session 9)

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

### Session 9 (2026-08-21) — full-system UI: transport indicator, verified badges, offline 1:1, delivery states
Branch `feat/full-system-ui`, built on top of `feat/transport-arbiter`. Goal: surface the
three-tier encrypted system in the UI, and close the two biggest gaps every prior session
flagged — no offline 1:1 messaging, no live transport indicator outside Emergency Mode.
**Compiled and verified this session**: `:app:compileDebugKotlin` and `:app:assembleDebug`
both succeed (JBR 17 + cached Gradle 8.5, throwaway placeholder `google-services.json` used
to verify and then deleted — same workaround as every prior session). **Not run on a
device** — this still inherits every on-device gap Sessions 3–8 left open (see §8);
everything below is code-reviewed + compile-verified only.

**Offline 1:1 messaging now exists — the headline gap from every prior session's list.**
`TransportArbiter.selectBearer` no longer hard-restricts targeted (1:1) packets to
`Tier.INTERNET`; all three tiers are now scored for a targeted packet the same as a broadcast
(`TransportArbiter.kt`). Two things had to be true for that restriction to come off safely:

- **`BleTransport` can now carry a targeted packet at all.** `BleMeshManager`'s flood/relay/
  dedup/outbox/chunking machinery only understands `BlePacket` (its `text` field is a '|'-joined
  SOS shape); a targeted `Packet`'s payload is opaque ciphertext, not that shape. Rather than
  touching any of that machinery (deliberately avoided — it's the most hardened, still-never-
  run-on-device code in the app), `BleTransport.sendToNextHop` now wraps a targeted packet's
  full serialized bytes (base64, which has no '|' in its alphabet) as the `text` field of a
  carrier `BlePacket`, and `BleTransport.incoming` unwraps it back on the other end
  (`wrapTargeted`/`unwrapTargeted`). Every relay hop still only ever sees an opaque carrier —
  relay-blindness holds — and the message rides the *existing* multi-hop flood for free, not
  just single-hop to a phone already in direct range. `WifiDirectTransport` needed **no
  changes at all**: it already carried a generic `Packet` end-to-end (Session 7).
- **Something has to consume a targeted packet arriving off BLE/Wi-Fi Direct.** New —
  **`data/repository/OfflineMessageRouter.kt`**: app-scoped (started from `AppContainer` with
  `appScope`, same lifetime as `TransportSendCoordinator`), merges
  `bleTransport.incoming`/`wifiDirectTransport.incoming`, and for any packet addressed to this
  device (`destId == myUid`) that isn't itself a delivery receipt: decrypts it (`CryptoManager`,
  via the peer's cached/fetched pubkey), appends it to the new **`data/local/
  OfflineMessageStore.kt`** (DataStore JSON per chatId, same pattern as `EmergencyHistoryStore`),
  and sends a `Packet.ack = true` receipt back to the sender. An incoming ack just bumps the
  matching locally-stored message's `DeliveryState` to `DELIVERED`.

**`Packet` gained an `ack: Boolean` field (wire version bumped 1 → 2).** `serialize()` always
writes it now; `deserialize()` only reads it when `version >= 2`, so the format stays honestly
versioned rather than silently assuming every packet has it. An ack's own `payload` is empty —
it proves arrival, not content, so relaying it blind is harmless.

**`ChatRepository` rewritten to merge online + offline history by msgId.** `observeMessages`
now `combine`s the existing Firestore snapshot flow with `OfflineMessageStore.observe(chatId)`
and de-dupes by `msgId`, keeping whichever copy has the more advanced `DeliveryState` (a message
that went out over BLE and *also* shows up on Firestore later keeps the Firestore-implied
`DELIVERED`). `InternetTransport.sendTargeted` now writes to `.document(packet.msgId)` instead
of Firestore's auto-generated `.add()` id, specifically so the two copies can collide on msgId.
`sendMessage` writes an **optimistic local echo** to `OfflineMessageStore` before attempting the
send (essential for BLE/Wi-Fi Direct, which have no Firestore snapshot to fall back on for the
sender's own copy) and updates its state after: `DELIVERED` if the arbiter picked
`Tier.INTERNET`, else `SENT` (upgraded to `DELIVERED` later if/when an ack arrives). New
**`data/repository/PeerKeyResolver.kt`** factors the peer-pubkey cache/fetch logic out of
`ChatRepository` so `OfflineMessageRouter` reuses the exact same resolution/caching instead of a
second copy of security-sensitive logic.

**Known simplification, called out honestly:** delivery state is derived at send time, not
tracked live through `TransportSendCoordinator`'s background retry tick — a message that starts
`QUEUED` and is later silently retried-and-sent by the coordinator's `availabilityChanges`
collector stays shown as "Queued" until an ack arrives (then jumps straight to "Delivered",
skipping "Sent"). Acceptable for this scope; a full fix would need the coordinator to expose a
per-message outcome callback, which didn't seem worth the coupling for a demo-scale app.
**Also:** a targeted send to a peer whose pubkey was never cached (no prior online contact, no
QR pairing) still throws "This contact hasn't set up encryption yet" even if they're right next
to you over BLE — this is intentional, not a gap: there's no unauthenticated key exchange over
Nearby, QR pairing (Session 5) is the out-of-band step SafeSphere already has for exactly this.

**Live transport indicator, everywhere.** New **`ui/components/TransportStatusChip.kt`**
(Internet / Wi-Fi Direct / Offline·BLE / buffering, reading `TransportSendCoordinator.
activeTier` + the new `bufferedCount`) replaces Emergency's bespoke inline `StatusBadge` and is
now also shown in Home's top bar and Chat's top bar — the same source of truth on every screen,
so the badge can't drift out of sync with what actually sent a message. `StoreCarryForwardQueue`
gained a `pendingCount: StateFlow<Int>` (updated on every enqueue/remove/load), exposed as
`TransportSendCoordinator.bufferedCount` — the "buffering" state the task asked for.

**Verified badges everywhere a contact's name appears.** New **`ui/components/
VerifiedBadge.kt`** (a check for verified, a quiet warning glyph otherwise) plus
`ContactSecurityStore.verifiedPeerUids(myUid): Flow<Set<String>>` (maps the whole DataStore
Preferences set rather than one Flow per row) now show on: Home's chat rows (`ChatSummary`
gained a `verified` field, populated by combining `observeRecentChats` with
`verifiedPeerUids`), Discover's All-Users and Nearby rows (`DiscoverUiState.verifiedUids`), and
the Chat top bar next to the peer's name (`ChatViewModel` now also collects
`ContactSecurityStore.isVerified`).

**Per-message delivery ticks in Chat.** `Message` gained `msgId` and a `state: DeliveryState`
(`QUEUED`/`SENT`/`DELIVERED`) field. `ChatScreen`'s own-message bubbles now show a text label
("Queued" / "Sent" / "Delivered ✓✓") next to the timestamp — text, not an icon, to avoid any
Material-icon-availability risk and to stay consistent with the app's existing text-forward,
elder-friendly bubble style. Received messages don't show a tick (delivery state is only
meaningful for the sender's own outgoing messages).

**Nearby → Chat already "just works" as offline 1:1 — no new screen needed.** Discover's
Nearby tab already opened the same `Routes.Chat`/`ChatScreen` every other entry point uses;
the fix was entirely in the data layer above. Tapping a BLE-discovered person now opens a
thread that actually sends/receives over BLE or Wi-Fi Direct when there's no internet, merging
with online history by msgId the moment connectivity returns, exactly as asked.

**`AppContainer` wiring:** new `peerKeyResolver`, `offlineMessageStore`,
`offlineMessageRouter` (`.also { it.start(appScope) }`, mirroring `transportSendCoordinator`).
`chatRepository`'s constructor changed shape: `(transportSendCoordinator, peerKeyResolver,
cryptoManager, offlineMessageStore)`, dropping the direct `userRepository`/
`contactSecurityStore` deps now that `PeerKeyResolver` owns that lookup.

**Not done / left for later:**
- **Still not run on a real device** — this is new routing/UI layered over transports
  (BLE/Wi-Fi Direct) that themselves have never run on a device (Sessions 3–7's gap, still
  open). The two/three-phone `docs/RUNTIME_TEST.md`/`docs/WIFIDIRECT_TEST.md` passes are the
  actual next step, now also exercising: send a 1:1 message with both phones offline but in
  BLE/Wi-Fi Direct range, confirm it arrives and the sender's tick flips to "Delivered", then
  bring both online and confirm the Home/Chat history doesn't duplicate the same message.
- Delivery-state live-tracking simplification (queued → sent transition can be skipped, see
  above) — documented, not fixed.
- No UI surface for *composing* a brand-new offline chat with someone not yet in Discover's
  Nearby list (unchanged — Nearby/All-Users are still the only entry points into Chat).
- The buffering indicator counts store-carry-forward entries app-wide, not scoped to the
  currently-open chat/emergency thread — a queued SOS and a queued chat message both show as
  one "buffering" count on every screen. Acceptable for this scope; per-chat buffering would
  need `StoreCarryForwardQueue` to expose counts keyed by `destId`, which it doesn't yet.

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

### Session 5 (2026-08-20) — end-to-end encryption + relay-node blindness for 1:1 messages
Branch `feat/e2e-encryption`. Goal: targeted 1:1 messages are now encrypted so Firestore (and any
future BLE/Wi-Fi-Direct relay) forwards ciphertext it cannot read. **The public SOS broadcast is
still deliberately plaintext** — see the threat-model note below for why. **Not run on a device or
compiled this session** — this machine has no Gradle/JDK/Android SDK (no cached Gradle like prior
sessions, no `./gradlew`), so this is code-reviewed only, not `:app:assembleDebug`-verified. Build
and run this before trusting it; the two crypto/QR dependency groups added below are the most
likely source of a real build error (version mismatches, ProGuard/R8 rules if minification is ever
turned on for release — currently off per `app/build.gradle.kts`).

**New — `data/security/CryptoManager.kt`.** Each user gets a long-term X25519 key pair, generated
by Tink's audited `subtle.X25519`. The scheme is **static X25519 ECDH + HKDF-SHA256 → AES-256-GCM**
(all three primitives are Tink's `subtle` classes — audited implementations used directly, not
hand-rolled crypto), *not* Tink's HPKE/ephemeral-key hybrid encryption API: a static per-pair key
means either side can re-derive it and decrypt their own sent history later, which ephemeral HPKE
can't do without a second ciphertext copy per message. The derived AES key is cached per peer.
`encryptFor(peerId, peerPubKeyBase64, plaintext)` / `decryptFrom(peerId, peerPubKeyBase64,
ciphertext)` are the two entry points; associated data is the deterministic `chatId` so ciphertext
can't be replayed into a different conversation. `bind(uid)` loads-or-generates the identity on
login/signup/cold-start; `clear()` drops the in-memory identity on logout (the on-disk keyset is
untouched, so logging back in as the same user restores the same identity + safety number).

**"Private key in the Keystore", honestly.** Raw X25519 key material cannot be generated/stored
natively inside the Android Keystore below API 31, and support is inconsistent even above it — this
app's minSdk is 24. So the private key bytes (from Tink's software X25519 implementation) are
stored via `androidx.security.crypto.EncryptedSharedPreferences`, whose wrapping AES key is a
`MasterKey` that **does** live in the Android Keystore (hardware-backed where available,
non-exportable). This is the standard, portable interpretation of "protected by the Keystore" for
asymmetric keys down to minSdk 24 — envelope encryption, not native raw-key Keystore residency.
Documented here instead of silently overclaiming it.

**`Packet.nonce` stays unused, on purpose.** Tink's `AesGcmJce` generates and bundles its own
random IV inside its ciphertext output, so there's no separate IV to carry in the packet header.
Using the wrapper's built-in IV handling (rather than pulling IV generation into our own code just
to populate the header field) avoids a whole class of nonce-reuse bugs for no real benefit.

**New — `data/security/SafetyNumber.kt`.** SHA-256 fingerprint of both users' (uid, pubKey) pairs,
order-independent — same string on both phones, read-aloud/compare style verification (Signal-style
"safety number"), for verifying a contact without a shared QR-scanning moment.

**New — `data/security/QrCodec.kt` + `ui/pairing/`.** `PairingScreen` always shows the current
user's own QR (uid + display name + base64 pubkey, ZXing-encoded to a `Bitmap`) and can switch to a
scan mode: a CameraX `PreviewView` + `ImageAnalysis` feeding frames directly into ZXing's
`MultiFormatReader` (no ML Kit — one barcode format, one screen, didn't need the extra dependency).
A successful scan caches the peer's pubkey and marks them **verified** immediately — the key came
straight off their device's screen in person, so that scan *is* the verification step; the safety
number is the fallback for verifying without a shared scan. Entry points: Profile → "My QR Code /
Verify a Contact" (no peer context — `Routes.Pairing.build()`), and a lock icon in `ChatScreen`'s
top bar scoped to that conversation's peer (`Routes.Pairing.build(peerUid, peerName)`). New
`data/local/ContactSecurityStore.kt` (DataStore) persists the local pubkey cache + verified flags,
keyed by `(myUid, peerUid)` so multiple accounts on one device don't cross-contaminate. Added
`CAMERA` permission + `android.hardware.camera` (`required="false"`) to the manifest.

**Wired into the send/receive path.** `ChatRepository.sendMessage` now resolves the peer's
published public key (local cache first — works offline once known, e.g. via QR pairing — else
`users/{uid}.pubKey` on Firestore), encrypts the text with `CryptoManager.encryptFor`, and puts the
ciphertext in `Packet.payload`; `InternetTransport.sendTargeted` writes it as a base64 `ciphertext`
Firestore field (replacing the old plaintext `text` field — no migration needed, there's no real
user data yet). `ChatRepository.observeMessages(chatId, myUid, peerId)` (signature gained `myUid`/
`peerId`, needed to resolve the pair's key) resolves the peer's key once per subscription and
decrypts every message — including the current user's own sent messages, since the shared key is
symmetric. A message that fails to decrypt (peer's key changed, corrupted data, whatever) renders
as a placeholder string instead of crashing or dropping the message. **The `chats/{chatId}` summary
doc's `lastMessage` field is now a static placeholder ("🔒 New message"), not real preview text** —
that doc is what the Home list preview reads from, and it must stay relay-blind same as the message
body. Accepted UX regression (Home no longer shows a live snippet); encrypting the summary too and
decrypting per-row in `observeRecentChats` is a reasonable follow-up if the preview is wanted back.

**Signup/login/cold-start now bind + publish the identity key.** `SignupViewModel.signup()` and
`LoginViewModel.login()` call `cryptoManager.bind(uid)` then `userRepository.publishPublicKey(uid,
...)` (best-effort — wrapped in `runCatching`, doesn't block the login/signup flow on a Firestore
write failure). `MainActivity.onCreate` does the same for a cold start that skips both screens
(existing session). `ProfileViewModel.logout()` calls `cryptoManager.clear()`. New
`UserRepository.publishPublicKey(uid, pubKeyBase64)`; `User`/`UserRepository.getUser`/`listUsers`/
`findByEmail` all gained a `pubKey` field.

**Threat model — what a relay (Firestore, or a future BLE/Wi-Fi-Direct relay hop) can and cannot
see, for a targeted 1:1 message:**
- **Cannot see:** message text (AES-256-GCM ciphertext only).
- **Can see:** `senderId`, `timestamp`, the `chatId` (derived from both uids), message size/timing/
  frequency (traffic metadata), and the fact that a conversation between two specific uids exists
  at all (the `chats/{chatId}` doc itself, and its participants list).
- **Not defended against:** a malicious or compromised Firestore admin swapping a user's published
  `pubKey` to mount a man-in-the-middle (this is exactly what QR pairing / safety-number comparison
  is for — an *unverified* contact has no cryptographic guarantee the key came from who they think
  it did, only that whoever holds the matching private key can read it). No forward secrecy (a
  single compromised private key decrypts that pair's entire history — static per-pair key, not a
  ratchet). No deniability/repudiation properties. Metadata (who talks to whom, when, how often) is
  fully visible to Firestore regardless.
- **The SOS broadcast stays plaintext by design**, unchanged from prior sessions' decision: it's
  meant to be read by *everyone* reachable, so there's no single recipient to encrypt it for, and
  "encrypting" a public broadcast to no one in particular is meaningless.

**Known gaps / left for later:**
- **Not compiled or run this session** (no Gradle/JDK/SDK on this machine) — build and manually
  verify signup → login → send/receive a 1:1 message → QR-pair two accounts → safety number matches
  on both, before trusting this.
- **No forward secrecy / no ratchet** — static per-pair key, as above. Fine for this project's scope
  (relay-blindness was the ask), but worth calling out if the paper claims Signal-protocol-level
  guarantees anywhere.
- **BLE/Wi-Fi-Direct tiers don't carry encrypted 1:1 traffic yet** — offline 1:1 messaging still
  doesn't exist at all (unchanged from Session 4's gap list); when it's built, `CryptoManager`
  already has everything it needs (peer pubkeys can be cached via QR pairing without any network),
  so encrypting that path should be a straightforward reuse, not new crypto design.
- **Reinstalling the app (or clearing app data) loses the identity key** and therefore all history
  encrypted under it — there's no key backup/export/restore UI. A fresh install generates a new
  identity and overwrites the old `pubKey` on Firestore; anyone who had that user's old key marked
  verified will see them as unverified again until re-paired. This is the standard trade-off for a
  device-resident identity key with no backup, not a bug, but undocumented anywhere else — flagging
  it here since it'll be confusing the first time someone hits it during testing.
- **Firestore rules/index unchanged** — the existing permissive rules in §7 (`allow read, write: if
  request.auth != null`) don't need updating for the new field names (`ciphertext` instead of
  `text`); they were never field-scoped to begin with.
- New Gradle dependencies this session (`app/build.gradle.kts`): `com.google.crypto.tink:
  tink-android:1.13.0`, `androidx.security:security-crypto:1.1.0-alpha06`, `com.google.zxing:
  core:3.5.3`, `androidx.camera:{camera-core,camera-camera2,camera-lifecycle,camera-view}:1.3.1`.
  None of these were previously in the project — first real dependency-resolution risk since the
  original Firebase/Compose BOM setup.

### Session 8 (2026-08-21) — per-hop Transport Arbiter + reliable delivery
Branch `feat/transport-arbiter`, built on top of `feat/wifi-direct-tier`. Goal: all three
`Transport` implementations existed (Session 4/7) but nothing chose between them — repositories
called BLE and Firestore directly, hard-coded. This session adds the per-hop arbiter and a
store-carry-forward send pipeline so that logic lives in one place. **Compiled and verified this
session**: `:app:compileDebugKotlin` and `:app:assembleDebug` both succeed (throwaway placeholder
`google-services.json`, deleted after — same workaround as Sessions 4/6/7). **Not run on a
device** — this is a pure routing/scoring change over already-existing, already-not-yet-verified
transports, so it inherits every on-device gap those sessions left (see §8).

**New — `data/transport/TransportArbiter.kt`.** `selectBearer(packet): ArbiterDecision` — either
`Candidates(orderedList)` or `StoreCarry`. Scores each `Tier` with a weighted sum over four
factors (`ArbiterWeights`, defaults `reachability=0.45, congestion=0.20, energy=0.15,
preference=0.20`, all constructor-configurable):
- **Reachability** — graded, not just on/off: `INTERNET` from `NetworkMonitor.currentlyOnline()`;
  `WIFI_DIRECT`/`BLE_MESH` score 1.0 with an active peer/socket, a lower 0.5–0.6 if the
  radio/group is up but nobody's connected yet (still eligible — sending into it still lands in
  that bearer's own outbox/relay), 0 (excluded outright) if the radio itself isn't up.
- **Congestion** — rough proxy from each bearer's own concurrency ceiling (`neighborCount` vs.
  `BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS`, `connectedSockets` vs. a soft cap for Wi-Fi
  Direct); `INTERNET` has no client-visible signal so it's always 1.0. Documented as deliberately
  coarse — neither bearer exposes real queue-depth telemetry.
- **Energy** — new `data/transport/EnergyMonitor.kt` (plain `BatteryManager` reads, no
  permission needed). Charging → energy stops mattering (1.0 everywhere). On battery,
  `WIFI_DIRECT` falls off hardest (`battery²`), `BLE_MESH` more gently, `INTERNET` not at all
  (marginal cost of reusing an already-open data connection is treated as negligible).
- **Priority** — `EMERGENCY` packets fold the congestion+energy weight into reachability instead
  (`effectiveWeights`): an SOS shouldn't be held back by efficiency concerns, it should just chase
  whatever path is most likely to deliver.
- **Preference** — the default tie-breaker/base ranking, `INTERNET(1.0) > WIFI_DIRECT(0.6) >
  BLE_MESH(0.3)`, per the task's required default order; also a constructor param.

**Targeted (1:1) packets are hard-restricted to `Tier.INTERNET`** in `selectBearer` — BLE/Wi-Fi
Direct can carry the ciphertext bytes today, but nothing on the receiving side consumes a
*targeted* packet off either transport (`ChatRepository.observeMessages` only ever listens to
Firestore). Recommending them would report `SENT` for a message the recipient's app can never
actually surface, so this is a deliberate, documented restriction (`TODO(offline 1:1)` in the
arbiter) rather than a silent gap — real offline 1:1 delivery still doesn't exist, unchanged from
every prior session's gap list. Broadcasts (SOS, `destId == null`) have no such restriction.

**New — `data/transport/TransportSendCoordinator.kt`.** The one send pipeline everything now goes
through: `send(packet): SendResult` (always `SENT` or `QUEUED`, never throws/`FAILED` to the
caller). Targeted packets try candidates **in order**, stopping at the first `SENT` — one intended
recipient, no reason to also try a worse tier. Broadcasts are **fanned out to every reachable
candidate** rather than stopping at the first — each bearer physically reaches a different
audience, so this preserves (and extends to three tiers) the pre-arbiter behaviour of always
dual-sending an SOS over BLE + Firestore. On total failure, enqueues into
`StoreCarryForwardQueue`. Exposes `activeTier: StateFlow<Tier?>` — updated on every successful send
and on every arbiter-availability change — as the new single source of truth for "which tier is
this app actually using right now," replacing the old plain online/offline boolean.

**New — `data/transport/StoreCarryForwardQueue.kt` + `data/local/StoreCarryForwardStore.kt`.**
Persisted (DataStore, JSON, same pattern as `EmergencyHistoryStore`/`ContactSecurityStore`) queue
of packets that failed on every currently-reachable tier, keyed by `msgId` (a re-enqueue of the
same message replaces the stale copy rather than duplicating). Expiry: 15 min for `NORMAL`
priority, 6 h for `EMERGENCY` (a stranded SOS is worth holding much longer than a stranded chat
message) — `pending()` drops anything past its deadline before returning. Packets are stored as
their own `Packet.serialize()` bytes (base64), so replaying one is just re-running
`TransportSendCoordinator`'s existing send logic against the same opaque bytes — **handover never
re-encrypts**, satisfying that constraint by construction rather than needing special-cased logic.

**Retry tick.** `TransportSendCoordinator.start(scope)` (called once from `AppContainer` with a
new app-lifetime `appScope`) collects `TransportArbiter.availabilityChanges` — a merge of
`NetworkMonitor.isOnline`, `BleMeshManager.status`, `WifiDirectManager.status` — and on every
change re-attempts every queued packet, removing each one that finally sends. This is
event-driven (fires when a bearer's status actually changes), not a fixed-interval poll.

**Repositories/ViewModels now route through the coordinator instead of picking a tier
themselves:**
- `EmergencyViewModel.sendSos` no longer calls `mesh.send()` directly or conditionally calls
  `EmergencyRepository.publish()` — it builds the same `BlePacket`/`Packet` as before and calls
  `container.transportSendCoordinator.send(packet.toPacket())` once. `EmergencyRepository.publish`
  was deleted (dead code once nothing called it); `EmergencyRepository` is now read-only
  (`observeEmergencies`) and its `InternetTransport` constructor param is gone.
- `ChatRepository.sendMessage` now takes a `TransportSendCoordinator` instead of an
  `InternetTransport`, calls `coordinator.send(packet)`, and **returns `SendResult`** instead of
  throwing on failure — a `QUEUED` result is not an error. `ChatViewModel.sendMessage` shows a
  neutral "will send automatically" notice for `QUEUED` instead of "Failed to send message".
- The chat-summary Firestore upsert in `sendMessage` is unconditional (even on `QUEUED`) since it
  was already only ever index bookkeeping for Home's list, not a delivery receipt.

**`AppContainer` wiring:** new `appScope` (`SupervisorJob + Dispatchers.Default`, outlives every
screen); `energyMonitor`, `transportArbiter`, `storeCarryForwardStore`, `storeCarryForwardQueue`,
`transportSendCoordinator` (constructed with a `Map<Tier, Transport>` of the three existing
transports, `.also { it.start(appScope) }`). `chatRepository` now takes `transportSendCoordinator`;
`emergencyRepository` takes no transport at all now.

**Status UI updated to reflect the active tier** (the task's explicit ask) — `EmergencyUiState`
gained `activeTier: Tier?`, populated by a new `EmergencyViewModel.observeActiveTier()` collecting
`transportSendCoordinator.activeTier`. `EmergencyScreen`'s `StatusBadge` now reads `"Internet"` /
`"Wi-Fi Direct"` / `"Offline · BLE"` from `activeTier` (falling back to the old online/offline
boolean only for the brief window before the first arbiter reading arrives) instead of a flat
online/offline flag. `InfoBanner`'s copy was intentionally left alone — it's already keyed off
`meshRunning`/`neighborCount`/`online` directly for BLE-specific troubleshooting detail that
`activeTier` alone doesn't carry (advertising vs. scanning vs. blocked-by-permissions), and
touching it wasn't asked for.

**TODO seam for the model-based arbiter (as scoped: rule-based now).** `TransportArbiter`'s
`scoreTier` carries a `TODO(ml-arbiter)` doc comment marking where a small on-device TF-Lite model
— trained on real `(reachability, congestion, energy, priority) -> outcome` samples once the
evaluation instrumentation from CLAUDE.md §5.5 exists — would plug in, without needing
`selectBearer`'s signature or `ArbiterDecision`'s shape to change.

**Not done / left for later:**
- **No offline 1:1 over BLE/Wi-Fi Direct still** — the arbiter's hard restriction (targeted
  packets → `Tier.INTERNET` only) makes this explicit and enforced rather than an accidental gap,
  but building the actual receive-side wiring (`ChatRepository` consuming `bleTransport.incoming`/
  `wifiDirectTransport.incoming` for targeted packets) is still future work.
- **Congestion scoring is a rough proxy** (concurrency-cap-relative, not real queue depth) — flagged
  in the arbiter's own doc comments; would need each manager to expose an actual pending-job count
  to do better.
- **Not run on a device.** Everything here is new routing/scoring logic layered over transports
  that themselves have never run on a device (Sessions 4–7's gap, still open) — see §8.
- **No evaluation instrumentation yet** (delivery ratio, latency, hop count, per-tier usage,
  battery — CLAUDE.md §5.5) — the arbiter's own decisions aren't logged anywhere yet beyond the
  existing `BleLog`/`WifiDirectLog` per-bearer traces plus one `SOS_SEND | path=arbiter |
  result=queued` line; a real evaluation pass would want the arbiter's own scores/decisions logged
  per send, not just the outcome.

### Session 7 (2026-08-20) — Wi-Fi Direct tier (the missing middle transport)
Branch `feat/wifi-direct-tier`, built on top of `feat/mesh-hardening`. Goal: implement the
`WIFI_DIRECT` tier behind the existing `Transport` interface — the stub from Session 4 is now
a real implementation. **Compiled and verified this session**: `:app:compileDebugKotlin` and
`:app:assembleDebug` both succeed (throwaway placeholder `google-services.json`, deleted
after — same workaround as Sessions 4/6). **Not run on a device** — see
`docs/WIFIDIRECT_TEST.md`, new this session, for the two-phone procedure (it also has to
document temporary manual triggers, since this session deliberately did not wire the tier
into any screen or arbiter — see below).

**New package — `data/transport/wifidirect/`** (mirrors the `ble/` package's shape and
`STEP | key=value` logcat convention, own tag `SafeSphereWifiDirect`):
- `WifiDirectManager.kt` — peer discovery (`WifiP2pManager.discoverPeers`, re-issued every
  20s since discovery auto-stops after ~2 min on most OEMs), group formation
  (`connectToPeer` → `WifiP2pManager.connect`, role decided by the platform's GO-intent
  negotiation, not requested), and a socket relay: the Group Owner runs a `ServerSocket` on
  port 8988 and accepts one socket per client; a non-GO client holds one long-lived socket to
  the GO (fixed address `192.168.49.1`, hard-coded per the stock-Android constraint — see the
  class doc for why that's safe). Exposes `status: StateFlow<WifiDirectStatus>`,
  `peers: StateFlow<List<WifiDirectPeer>>`, `incoming: SharedFlow<Packet>` (replay=32, same
  as `BleMeshManager.incoming`), and `suspend fun send(packet): Boolean`.
- `WifiDirectFrame.kt` — 4-byte length-prefix framing for `Packet` bytes over the TCP stream
  (TCP has no message boundaries, unlike one BLE GATT write); no chunking needed since TCP
  already handles fragmentation/reassembly at the stream level, unlike `BleChunk`.
- `WifiDirectPermissions.kt` — `NEARBY_WIFI_DEVICES` on API 33+ (declared `neverForLocation`,
  same reasoning as BLE's `BLUETOOTH_SCAN`), `ACCESS_FINE_LOCATION` below that. Reuses
  `BlePermissions.isLocationServiceOn` for the pre-13 location-toggle check rather than
  duplicating it — that check was never actually BLE-specific.
- `WifiDirectConstants.kt`, `WifiDirectLog.kt`, `WifiDirectPeer.kt` — same shape as their
  `ble/` counterparts.
- `WifiDirectTransport.kt` — wraps `WifiDirectManager` as a `Transport`, same pattern as
  `BleTransport` wraps `BleMeshManager`. `nextHop` is ignored (the group is a star; `send`
  already fans out to every connected socket).

**Topology and the "one group" constraint.** Stock Android only ever gives an app one active
Wi-Fi Direct group; every client's traffic flows through the Group Owner at the fixed address
`192.168.49.1`. Within that one group, `WifiDirectManager.handleIncomingFrame` makes the star
behave like the BLE flood for a single hop: the GO relays an incoming packet to every *other*
connected client. **Multi-group multi-hop (the general mesh case) is explicitly out of scope
this session** — documented as a "Path to multi-hop" note in `WifiDirectManager`'s class doc
rather than attempted, since it needs either repeated group teardown/reformation or newer,
more restrictive platform APIs this session didn't have time to evaluate.

**Manifest (`AndroidManifest.xml`).** Added `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`,
`CHANGE_NETWORK_STATE`, `NEARBY_WIFI_DEVICES` (`neverForLocation`), and
`<uses-feature android:name="android.hardware.wifi.direct" android:required="false" />`.
**Extended the existing `ACCESS_FINE_LOCATION` entry's `maxSdkVersion` from 30 to 32** — BLE
only needed it capped at 30 (exempt via `neverForLocation` at API 31+), but Wi-Fi Direct
peer discovery rides the platform's Wi-Fi scan subsystem and needs location up through API 32
regardless; `NEARBY_WIFI_DEVICES` only takes over at 33.

**`AppContainer` wiring:** new singleton `wifiDirectManager: WifiDirectManager`;
`wifiDirectTransport` now wraps it instead of being the Session 4 stub. The old
`data/transport/WifiDirectTransport.kt` stub file was deleted — the real implementation
lives in `data/transport/wifidirect/` instead, matching where `BleTransport` lives relative
to `BleMeshManager`. No other file referenced the stub (confirmed by grep before deleting).

**Not done / left for later:**
- **Not wired into any screen or the per-hop arbiter.** Nothing calls
  `wifiDirectManager.start()` or `connectToPeer()` yet — there's no arbiter to decide when to
  prefer Wi-Fi Direct over BLE/Internet, and no screen exposing "nearby via Wi-Fi Direct" the
  way `EmergencyScreen`/`DiscoverScreen` do for BLE. `docs/WIFIDIRECT_TEST.md` §0.2/§3
  documents the temporary manual hooks needed to exercise it on a device right now.
- **No persistent group / auto-reconnect.** A dropped connection requires calling
  `connectToPeer` again by hand; there's no BLE-tier-style retry/backoff loop for it.
- **No offline 1:1 over Wi-Fi Direct** — `Packet.destId` is carried end-to-end so a future
  arbiter/repository change could route a targeted message this way, but nothing does yet.
- **Not run on a real device this session** — code-reviewed and `:app:assembleDebug`-verified
  only; `docs/WIFIDIRECT_TEST.md` is the actual next step, on two (ideally three, for the
  relay test) phones.
- **`CLAUDE.md` was corrupted** at the start of this session (it contained leftover assistant
  chat narration wrapped around the real content in a markdown fence, from some prior
  process accidentally writing chat output into the file instead of just its content) —
  cleaned up as a drive-by fix, unrelated to the Wi-Fi Direct work itself.

### Session 6 (2026-08-20) — mesh reliability hardening (dense / intermittent conditions)
Branch `feat/mesh-hardening`, built on top of `feat/e2e-encryption`. Goal: the mesh already
worked for a clean two-phone demo (Session 3); this session hardens it for a **dense room and
flaky links** — bounded concurrency, retries, bounded memory, storm avoidance, real fragmentation,
and battery duty-cycling. **Compiled and verified this session**: `:app:compileDebugKotlin` and
`:app:assembleDebug` both succeed (throwaway placeholder `google-services.json` used to verify,
then deleted — same workaround as Session 4). **Not run on a device** — still needs the two-phone
(ideally three-phone, for relay/dedup) pass from `docs/RUNTIME_TEST.md`, now also covering the
scenarios below.

All new knobs live in `data/transport/ble/BleConstants.kt`, defaults chosen to be safe (don't
regress the untested mesh's baseline behavior) while still doing real work:

1. **Capped concurrent GATT connections** (`MAX_CONCURRENT_GATT_CONNECTIONS = 5`). The GATT
   *client* worker was a single fully-serial loop (effectively cap = 1); it's now a bounded pool —
   `startWorker()`'s dispatch loop still checks writes before identity reads every iteration (same
   priority as before), but each job now runs on its own coroutine gated by a `Semaphore(5)`
   (`connectionSemaphore`), so up to 5 *different* devices can be mid-connection at once instead of
   one at a time. A new per-device `Mutex` (`deviceMutex`, one per address in `deviceMutexes`)
   wraps `sendToDevice`/`readIdentity` so two workers can never dial the *same* device
   concurrently — Android's stack doesn't tolerate that regardless of the overall cap.
2. **Reconnect/retry with exponential backoff + jitter** (`RETRY_BASE_BACKOFF_MS = 500`,
   `RETRY_MAX_BACKOFF_MS = 8_000`, `RETRY_JITTER_MS = 300`). A failed write or identity read used
   to just increment a failure counter and wait for the *next* unrelated opportunity (outbox
   flush / next scan) to retry. Now `scheduleRetry()` actively re-queues the job after
   `retryBackoffMs(attempt)` (`base * 2^(attempt-1)` capped at max, plus jitter) — up to
   `NEIGHBOR_FAILURE_LIMIT` (3) attempts before a write-failing neighbour is dropped (unchanged
   threshold) or an identity read gives up (new: previously only stopped via the 120s sweep).
3. **Bounded + TTL-evicted seen-set** (`SEEN_MAX = 500` unchanged, new `SEEN_TTL_MS = 15 min`).
   `seen` changed from `LinkedHashSet<String>` to `LinkedHashMap<String, Long>` (msgId → first-seen
   time): `addSeen()` still trims to `SEEN_MAX` on overflow, and the sweeper now also calls
   `evictExpiredSeen()` every `SWEEP_INTERVAL_MS` to drop anything older than the TTL, so a quiet
   mesh doesn't hold onto old ids indefinitely between the two bounds.
4. **Stale `NearbyPeer` eviction** — already existed (`PEER_STALE_MS = 120s`, swept in
   `startSweeper()` since Session 3); left as-is, just confirmed it satisfies this session's ask.
5. **Randomized re-broadcast jitter** (`REBROADCAST_JITTER_MAX_MS = 400`). `handleIncoming()`'s
   relay branch now delays `Random.nextLong(0, 401)` ms (logged as `jitterMs`) before calling
   `enqueueToNeighbors()`, so phones that all just received the same broadcast in the same instant
   don't all hit the same next-hop neighbours' GATT servers simultaneously. Only applies to
   *relays*; the original sender's first broadcast is unchanged (still immediate).
6. **Application-level chunking + reassembly above the negotiated MTU** — new file
   `data/transport/ble/BleChunk.kt`: every outbound write is now wrapped in a `groupId(4) +
   seq(2) + total(2)` envelope (`CHUNK_HEADER_SIZE = 8`) and split to fit
   `negotiatedMtu - 3 - 8` bytes per chunk (floor `CHUNK_MIN_PAYLOAD = 20`, matching the default
   23-byte ATT MTU if negotiation fails). This replaces reliance on Android's own inconsistent
   long-write/prepared-write fragmentation for anything we send: `sendToDevice()` now sends one
   connection's chunks sequentially, waiting for each write ack before the next, and only resolves
   `TX_OK` once all chunks succeed. On the receiving side, `handleChunkWrite()` parses each
   envelope; a single-chunk message (the common case — most SOS/chat text fits in one MTU-sized
   write) is delivered immediately, multi-chunk messages accumulate in a new `reassembly` map
   (keyed `"address:groupId"`) until complete, then get reassembled and handed to `acceptBytes()`.
   An incomplete reassembly (sender vanished mid-transfer) is dropped by the sweeper after
   `REASSEMBLY_TTL_MS = 20s`. The old native `preparedWrite`/`onExecuteWrite` path (Session 3) is
   left in place defensively but should no longer trigger, since our own writes never exceed the
   negotiated MTU; if it ever does fire, its reassembled buffer is now also routed through
   `handleChunkWrite()` instead of being force-fed to `acceptBytes()` directly.
7. **BLE duty-cycling** for battery. Scan: `SCAN_DUTY_CYCLE_ENABLED = true` by default,
   `SCAN_WINDOW_ON_MS = 10s` / `SCAN_WINDOW_OFF_MS = 5s` — `startScanDutyCycleLoop()` alternates
   `stopScan()`/`startScanning()` on that cadence (only while the mesh is running; cancelled in
   `teardownRadio()` and restarted after a Bluetooth-off/on cycle same as the other radio jobs).
   Advertise: `ADVERTISE_DUTY_CYCLE_ENABLED = false` by default — an OFF window makes this phone
   briefly *undiscoverable*, a bigger reliability risk than the battery it saves for an
   already-fragile, never-run-on-device mesh — but the identical mechanism
   (`startAdvertiseDutyCycleLoop()`, `ADVERTISE_WINDOW_ON_MS`/`ADVERTISE_WINDOW_OFF_MS`, both 10s/5s)
   exists and is a one-line flip in `BleConstants` for anyone who wants to trade discoverability
   for battery life.

**Bounded/politely-dropped job queues** (not explicitly asked for, but required to make #1 safe):
`writeQueue`/`identityQueue` changed from `Channel.UNLIMITED` to a bounded
`BleConstants.JOB_QUEUE_CAPACITY = 256`. `trySendWrite()`/`trySendIdentityRead()` wrap the
`trySend()` calls; on overflow they log `QUEUE_FULL` and roll back the just-set "delivered" /
`identityRequested` marker so a later opportunity (outbox flush, next scan result) retries instead
of the message silently vanishing for that neighbour.

**New `BleLog.Step` constants**: `QUEUE_FULL`, `RETRY_SCHEDULE`, `REASSEMBLY_DROP`, `DUTY_CYCLE`.

**Not done / left for later:**
- **Still not run on a real device.** The two/three-phone `docs/RUNTIME_TEST.md` pass is the
  actual next step, now also exercising: a dense room (≥5 neighbours) to see the concurrency cap
  and retry backoff in the logs; a message long enough to force multi-chunk (`CHUNK` log lines,
  `chunks > 1`); toggling one phone's Bluetooth mid-broadcast to see retry-then-drop; and just
  watching battery drain with `SCAN_DUTY_CYCLE_ENABLED` on vs. off.
- Concurrency cap and duty-cycle windows are compile-time constants in `BleConstants`, not
  runtime-configurable from a settings screen — "configurable" here means "one constant to change
  and rebuild," per the task's own phrasing, not a user-facing setting.
- The advertise duty-cycle path is implemented but disabled by default (see #7) — enabling it is
  an explicit tradeoff someone should make deliberately, not a default for an unverified mesh.

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
- **Encryption implemented for targeted 1:1 messages as of Session 5** (X25519 + HKDF + AES-256-GCM,
  `data/security/CryptoManager.kt`) — Firestore only ever sees ciphertext for 1:1 chat. **The SOS
  broadcast stays intentionally unencrypted** — it's a public broadcast to everyone reachable, so
  E2E-to-one-recipient is meaningless; encrypting it would mean nobody could read it.

---

## 4. Architecture (current package layout)

```
com.capstone.chatapp/
  MainActivity.kt              # single Activity; applies persisted theme + hosts NavHost
  ChatApp.kt                   # Application; builds AppContainer (manual DI)
  di/AppContainer.kt           # holds all repositories + BleMeshManager + NetworkMonitor (singletons)
  navigation/
    Routes.kt                  # login, signup, home, discover, chat/{peerUid}, profile, emergency, pairing
    AppNavHost.kt              # NavHost wiring
  ui/
    theme/                     # Color.kt, Type.kt, Theme.kt (Material3 light+dark, ThemeMode enum)
    components/                # AppTextField, LoadingButton, AuthScaffold (shared UX)
                               #   + NEW Session 9: TransportStatusChip, VerifiedBadge (shared
                               #   across Home/Chat/Emergency and Home/Discover/Chat resp.)
    util/     TimeFormat.kt    # formatTime(Long) / formatTime(Timestamp) for message times
    login/    LoginScreen + LoginViewModel      # also binds+publishes the E2E identity key on sign-in
    signup/   SignupScreen + SignupViewModel    # also binds+publishes the E2E identity key on sign-up
    home/     HomeScreen + HomeViewModel        # conversation list (recent chats) + Discover/Profile/SOS entries
    discover/ DiscoverScreen + DiscoverViewModel  # All Users (Firestore) + Nearby (BLE) tabs
    chat/     ChatScreen + ChatViewModel        # realtime 1:1, theme-aware bubbles + timestamps + Verify action
    profile/  ProfileScreen + ProfileViewModel  # username, theme selector, logout, My QR Code entry
    emergency/ EmergencyScreen + EmergencyViewModel  # SOS broadcast, chat-styled bubbles + persisted history
    pairing/  PairingScreen + PairingViewModel  # NEW Session 5: QR show/scan + safety-number display
  data/
    model/        Message.kt, User.kt (+ pubKey), ChatSummary.kt
    local/        EmergencyHistoryStore.kt      # DataStore-persisted SOS history (JSON)
                   ContactSecurityStore.kt      # NEW Session 5: cached peer pubkeys + verified
                                                 #   flags; NEW Session 9: verifiedPeerUids(myUid)
                                                 #   for list-screen badges
                   OfflineMessageStore.kt        # NEW Session 9: DataStore-persisted local
                                                 #   history for 1:1 messages sent/received over
                                                 #   BLE/Wi-Fi Direct, keyed by chatId
    security/      NEW Session 5 — E2E encryption
      CryptoManager.kt        # X25519 identity (Keystore-wrapped) + HKDF + AES-256-GCM encrypt/decrypt
      SafetyNumber.kt         # order-independent fingerprint of two pubkeys, for manual verification
      QrCodec.kt              # pairing QR payload encode/parse + ZXing bitmap generation
    repository/    AuthRepository, UserRepository (listUsers, pubKey, publishPublicKey),
                   PeerKeyResolver (NEW Session 9 — peer-pubkey cache/fetch, shared by
                   ChatRepository and OfflineMessageRouter),
                   ChatRepository (observeMessages NEW Session 9 — merges Firestore online
                   history with OfflineMessageStore by msgId; sendMessage writes an optimistic
                   local echo then goes through TransportSendCoordinator, returns SendResult
                   instead of throwing), OfflineMessageRouter (NEW Session 9 — app-scoped;
                   decrypts targeted packets arriving off BLE/Wi-Fi Direct, persists them,
                   sends a Packet.ack receipt back), EmergencyRepository (Firestore
                   `emergencies` read-only as of Session 8, still plaintext by design —
                   sending moved to the arbiter), SettingsRepository (DataStore)
    transport/
      Tier.kt                 # enum INTERNET / WIFI_DIRECT / BLE_MESH
      Packet.kt               # transport-agnostic wire format: cleartext header + opaque payload
      Transport.kt            # interface every bearer implements + SendResult enum
      InternetTransport.kt    # Transport over Firestore (emergencies plaintext, chats/{chatId}/messages ciphertext)
      NetworkMonitor.kt        # validated-internet flow (online/offline)
      EnergyMonitor.kt         # NEW Session 8 — BatteryManager fraction/charging read, no permission needed
      TransportArbiter.kt      # NEW Session 8 — selectBearer(packet): weighted score over
                               #   reachability/congestion/energy/priority per Tier; ArbiterDecision
                               #   (Candidates, ordered) | StoreCarry; targeted (1:1) packets hard-
                               #   restricted to INTERNET (no offline-1:1 receive-side wiring yet)
      TransportSendCoordinator.kt # NEW Session 8 — the one send pipeline: ordered try for targeted,
                               #   fan-out-to-all for broadcasts, SCF enqueue on total failure,
                               #   retry tick on arbiter.availabilityChanges, activeTier StateFlow
      StoreCarryForwardQueue.kt   # NEW Session 8 — persisted (via data/local/StoreCarryForwardStore.kt)
                               #   queue of packets with no reachable tier; TTL 15min normal / 6h emergency
      wifidirect/              # peer discovery + group formation + socket relay (Session 7) — see below
      ble/
        BleConstants.kt        # UUIDs, TTL=10, REACH_CAP=100, MTU=185, timeouts/eviction/outbox windows
                               #   + NEW Session 6: concurrency cap, retry/backoff, seen TTL, jitter,
                               #   chunking, duty-cycle knobs (see Session 6 changelog for every default)
        BleChunk.kt            # NEW Session 6: chunk envelope (groupId+seq+total) split/parse for
                               #   application-level fragmentation above the negotiated MTU
        BleLog.kt              # single logcat tag "SafeSphereBLE"; step vocabulary + error-code decoding
        BlePacket.kt           # SOS fields; wraps/produces Packet (toPacket/fromPacket) + relayed()
        BleTransport.kt        # Transport wrapping BleMeshManager (flood broadcast, nextHop ignored)
        NearbyPeer.kt          # a person discovered nearby (uid, name, address, lastSeen)
        BleMeshManager.kt      # advertiser + GATT server + scanner + GATT client + flood relay
                               #   + identity read → nearbyPeers + outbox + neighbour eviction
                               #   + BT-state receiver; GATT client work on a bounded (5) concurrent
                               #   pool with per-device locking (writes still win) — Session 6
                               #   + retry/backoff, bounded+TTL seen-set, relay jitter, chunking,
                               #   scan/advertise duty-cycling — all Session 6
        BleMeshService.kt      # foreground service (type connectedDevice) keeping mesh alive
        BlePermissions.kt      # runtime perms per SDK level; essential vs optional; location-toggle check
      wifidirect/              # Session 7 — peer discovery, one-group formation, socket relay
        WifiDirectManager.kt   # discoverPeers/connect, GO ServerSocket + client socket, status/incoming
        WifiDirectTransport.kt # Transport wrapping WifiDirectManager (star fan-out, nextHop ignored)
        WifiDirectFrame.kt, WifiDirectPermissions.kt, WifiDirectConstants.kt, WifiDirectLog.kt, WifiDirectPeer.kt
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
- [x] Per-hop Transport Arbiter (`TransportArbiter` + `TransportSendCoordinator`, Session 8) —
      replaces the ad-hoc BLE-plus-Firestore dual-send; broadcasts fan out across every reachable
      tier, targeted 1:1 sends are ordered/restricted to Internet, failed sends persist to a
      store-carry-forward queue and retry automatically — ⚠ new routing over transports that
      themselves are still not run on a device
- [x] Status badge shows the active tier (Internet / Wi-Fi Direct / Offline·BLE), not just a flat
      online/offline flag — nearby-device count unchanged
- [x] E2E encryption for targeted 1:1 messages (X25519 + HKDF + AES-256-GCM) — ⚠ not yet
      compiled/run on a device this session; SOS broadcast stays unencrypted by design
- [x] QR pairing + safety-number verification for contacts (`ui/pairing/PairingScreen.kt`)
- [x] Offline 1:1 over BLE / Wi-Fi Direct (Session 9) — `TransportArbiter` scores all three
      tiers for a targeted packet now; `OfflineMessageRouter` decrypts/persists/acks packets
      arriving off BLE/Wi-Fi Direct; `ChatRepository` merges that local history with Firestore
      by msgId. Nearby → Chat now delivers offline instead of dead-ending. ⚠ not run on a device

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
- [x] Live transport indicator (Internet/Wi-Fi Direct/BLE/buffering) on Home, Chat, Emergency
      (Session 9) — `ui/components/TransportStatusChip.kt`, one shared source of truth
- [x] Verified/unverified contact badge on Home, Discover, Chat (Session 9) —
      `ui/components/VerifiedBadge.kt`
- [x] Per-message delivery state (Queued/Sent/Delivered) on own chat bubbles (Session 9)

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
- **Encryption for 1:1 chat implemented in Session 5** but **still not run on a device** (it does now
  compile, confirmed in Session 6 — see below). SOS broadcast stays unencrypted by design.
- ~~Battery duty-cycling~~ / ~~simultaneous-GATT-connection limit~~ / ~~message chunking above the
  MTU~~ — **all addressed in Session 6** (scan duty-cycling on by default, 5-connection concurrent
  pool with per-device locking, application-level chunking + reassembly). See the Session 6
  changelog above. Advertise duty-cycling exists but is off by default (deliberate — see changelog).

**Build status as of Session 6:** `:app:compileDebugKotlin` and `:app:assembleDebug` **both succeed**
on this machine (JDK 17 via Android Studio's bundled JBR, Android SDK present, cached Gradle 8.5) —
this resolves Session 5's "never compiled" gap for the E2E-encryption code too, since it's on the
same branch lineage. A throwaway placeholder `google-services.json` was used to verify the build and
then deleted, same as Session 4 — **do not ship a fake one**, it builds fine but every Firebase call
fails at runtime.

**Recommended next step:** drop in a real `google-services.json`, then run `docs/RUNTIME_TEST.md`
end to end on two (ideally three, for relay/dedup) phones and bring back the `SafeSphereBLE` logcat
from all of them — now also watching for `chunks`, `RETRY_SCHEDULE`, `QUEUE_FULL`, `REASSEMBLY_DROP`
and `DUTY_CYCLE` lines from Session 6's changes. Also manually verify the encryption path: sign up
two accounts, send a 1:1 message both ways, QR-pair them from `Profile → My QR Code / Verify a
Contact`, and confirm the safety number matches on both phones.

---

## 9. Key reference files

- Plan: `/Users/rishibhardwaj/.claude/plans/federated-swinging-bachman.md`
- **Two-phone runtime test + logcat guide: `docs/RUNTIME_TEST.md`** (start here for any BLE work)
- Existing docs: `README.md`, `WEEK2_README.md`, `FIREBASE_SETUP.md`
- Persistent memory (loaded each session): `.claude/.../memory/MEMORY.md`
  (no-tests preference, build workaround, encryption-deferred rationale)
