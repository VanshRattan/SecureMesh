# WIFIDIRECT_TEST.md — verifying the Wi-Fi Direct tier on two real phones

Wi-Fi Direct, like BLE, has no emulator support — Android emulators have no Wi-Fi Direct
radio. Everything below needs **two physical Android phones** (a third is useful once you
want to confirm the group-owner relay fans out to more than one client, same as the BLE
mesh's 3-phone relay test).

Every step logs under a single logcat tag, **`SafeSphereWifiDirect`**.

---

## 0. Before you start

### 0.1 Build and install

Same as `docs/RUNTIME_TEST.md` §0.1–0.2 — this tier needs `app/google-services.json` in
place for the app to build at all (Firebase, unrelated to Wi-Fi Direct, still gates the
build), and the same cached-Gradle build command:

```powershell
# Windows / PowerShell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$gradle = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.5-bin\*\gradle-8.5\bin\gradle.bat"
& (Resolve-Path $gradle) :app:assembleDebug
```

```bash
# macOS / Linux
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
GRADLE=$(echo ~/.gradle/wrapper/dists/gradle-8.5-bin/*/gradle-8.5/bin/gradle)
"$GRADLE" :app:assembleDebug
```

Install on both phones:

```bash
adb devices
adb -s <SERIAL_A> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <SERIAL_B> install -r app/build/outputs/apk/debug/app-debug.apk
```

### 0.2 There is no screen wired to this tier yet — add a temporary trigger

This session only implements the transport (`WifiDirectManager` + `WifiDirectTransport`,
registered in `AppContainer`) and its permissions — there is no per-hop arbiter or UI screen
calling `wifiDirectManager.start()` yet (same category of gap as "Discover → Nearby never
starts the [BLE] mesh itself" in `SESSION_CONTEXT.md` §6 — wiring a tier into UI is separate
from implementing it). To exercise it on a device before that wiring exists, add one
temporary line — remove it once a real caller exists:

```kotlin
// MainActivity.kt, inside onCreate(), after super.onCreate(...) — TEMPORARY, remove once
// the transport arbiter or a screen starts this tier for real.
appContainer().wifiDirectManager.start()
```

This starts discovery immediately on app launch on both phones, which is enough to test
everything below. `WifiDirectManager.start()` internally checks permissions/Wi-Fi state via
`precheck()` and logs `WD_PRECHECK | aborted=<reason>` if it can't run — check logcat first
if nothing happens.

### 0.3 Phone requirements

- Android 7.0 (API 24) or newer — the app's `minSdk`.
- Both phones must support Wi-Fi Direct (`PackageManager.FEATURE_WIFI_DIRECT`) — true for
  the overwhelming majority of Android phones since Wi-Fi Direct is effectively mandatory
  for the platform's own Wi-Fi Direct-based features (Nearby Share on older versions, some
  OEM file-share tools).
- Wi-Fi must be **on** on both phones (Wi-Fi Direct rides the Wi-Fi radio; it does not need
  an access point or internet — turn on airplane mode then re-enable only Wi-Fi if you want
  to prove there's no AP involved).
- Grant the permission prompt when it appears — see §2.

---

## 1. Open the log stream (do this first, in two terminals)

```bash
# Terminal 1
adb -s <SERIAL_A> logcat -c && adb -s <SERIAL_A> logcat -s SafeSphereWifiDirect:V

# Terminal 2
adb -s <SERIAL_B> logcat -c && adb -s <SERIAL_B> logcat -s SafeSphereWifiDirect:V
```

Lines are `STEP | key=value key=value`, same convention as `SafeSphereBLE` — filter further
with `| grep RX_PACKET` etc.

---

## 2. Permission flow

Since no screen requests `WifiDirectPermissions.essential()` yet either (see §0.2), the
system permission dialog **will not appear on its own** — `WifiDirectManager.start()` will
just log `WD_PRECHECK | aborted=Missing permission: ...` and stop. Grant it manually before
testing:

```bash
# Android 13+ (API 33+)
adb -s <SERIAL> shell pm grant com.capstone.chatapp android.permission.NEARBY_WIFI_DEVICES

# Android 12 and below
adb -s <SERIAL> shell pm grant com.capstone.chatapp android.permission.ACCESS_FINE_LOCATION
```

On Android 12 and below, also confirm the system **Location** toggle is on (Settings →
Location) — `WD_PRECHECK | locationService=off` in the log means that, not the permission,
is the blocker. (Android 13+ with `NEARBY_WIFI_DEVICES` doesn't need this.)

Relaunch the app (or just re-trigger `onCreate`) after granting.

---

## 3. Expected trace — discovery and group formation

Launch the app on both phones (with the §0.2 trigger in place). Expected sequence, both
phones:

1. `WD_START | sdk=<n>` — manager started.
2. `WD_PRECHECK | ok=true` — no blocker.
3. `WD_DISCOVER_START` — peer discovery kicked off.
4. `WD_PEERS_CHANGED | count=1` on each phone once they see each other (device names, not
   app identities — Wi-Fi Direct only exposes the P2P device name at this stage, same
   caveat as raw BLE scan results before the identity read).

**Discovery alone does not form a group** — Wi-Fi Direct requires one side to explicitly
initiate a connection. This session doesn't wire that into UI either (see §0.2), so trigger
it directly. The easiest way without adding a screen: temporarily call it right after
`discoverPeers()` once you've confirmed a peer showed up, e.g. from an `adb shell` Kotlin
REPL is not available on stock Android, so instead add a second temporary line on **one**
phone only (the "initiator"):

```kotlin
// One phone only — after peers are visible. Replace with a real address from
// WD_PEERS_CHANGED / the peers StateFlow (e.g. log it and read from logcat).
appContainer().wifiDirectManager.connectToPeer("AA:BB:CC:DD:EE:FF")
```

5. `WD_CONNECT_REQUEST | peer=...` on the initiator.
6. Both phones show a **system Wi-Fi Direct invitation dialog** — accept it on the
   non-initiating phone (this is standard Android P2P UX, not something the app controls).
7. `WD_CONNECTION_CHANGED | groupFormed=true isGroupOwner=<true on one phone, false on the
   other>` on both phones — the platform decides which one is the Group Owner (GO); don't
   assume it's the initiator.
8. GO's log: `WD_GROUP_FORMED | role=OWNER goAddress=192.168.49.1` then
   `WD_SERVER_START | port=8988`.
9. Client's log: `WD_GROUP_FORMED | role=CLIENT goAddress=192.168.49.1` then
   `WD_CLIENT_CONNECT | host=192.168.49.1` → `WD_CLIENT_CONNECT_OK`.
10. GO's log: `WD_CLIENT_ACCEPTED | peer=<client's group IP> total=1`.

If you stall at step 6 with nothing happening, the invitation may have gone to a
notification instead of a dialog on some OEM skins — check the notification shade.

---

## 4. Expected trace — sending a packet

Once §3 step 10 has happened on both phones (group formed, sockets up,
`WD.status.value.groupFormed == true` on both), send a packet from either side. There's no
UI for this yet (`ChatRepository`/`EmergencyRepository` don't route through
`WifiDirectTransport` this session — see `SESSION_CONTEXT.md`), so drive it directly, e.g.
temporarily from a button `onClick` or a one-off coroutine:

```kotlin
lifecycleScope.launch {
    val packet = Packet(
        msgId = java.util.UUID.randomUUID().toString(),
        destId = null,
        srcId = appContainer().authRepository.currentUid,
        ttl = 10,
        priority = Priority.NORMAL,
        tierTag = Tier.WIFI_DIRECT,
        nonce = ByteArray(0),
        payload = "hello over wifi direct".toByteArray(),
    )
    val result = appContainer().wifiDirectTransport.sendToNextHop(packet, null, Tier.WIFI_DIRECT)
    Log.d("WifiDirectTestSend", "result=$result")
}
```

Expected, sender side: `WD_TX_OK | msgId=<8 chars> to=GO` (if sender is the client) or
`WD_TX_OK | msgId=<8 chars> targets=1` (if sender is the GO).

Expected, receiver side: `WD_RX_PACKET | msgId=<same 8 chars> from=<peer> ttl=10`.

With a **third** phone also joined to the same group (connect it to the GO the same way as
in §3, step 5–6), sending from a client should additionally show `WD_RELAY | msgId=<...>
to=1` on the GO's log, and the third phone should also log `WD_RX_PACKET` for that message —
confirming the star relay (§ design note in `WifiDirectManager`'s class doc).

---

## 5. Failure / recovery checks worth exercising

- **Turn off Wi-Fi on the client mid-group.** Expect `WD_SOCKET_CLOSED` on both sides, then
  `WD_CONNECTION_CHANGED | groupFormed=false` and `WD_GROUP_LOST` once the platform notices
  the link is gone. Turning Wi-Fi back on does **not** auto-rejoin the group (no persistent
  group support wired up this session) — you'll need to `connectToPeer` again.
- **Kill and relaunch the app on the client only, GO still up.** Expect the GO to log
  `WD_SOCKET_CLOSED` for that client's socket (TCP FIN/RST) and drop it from
  `connectedSockets`.
- **A message longer than one TCP segment.** `WifiDirectFrame` length-prefixes instead of
  chunking (TCP already handles fragmentation/reassembly at the stream level, unlike BLE's
  GATT writes — see the class doc on `WifiDirectFrame`), so this should just work
  transparently; there's no MTU-style ceiling to hit. Worth sending a long string anyway to
  confirm `readFully`'s loop actually loops instead of assuming one `read()` call returns
  everything.

---

## 6. Known limitations of this session's implementation (see `SESSION_CONTEXT.md`)

- **Single group only.** Stock Android gives an app one Wi-Fi Direct group at a time: all
  traffic in that group flows through its Group Owner. Multiple simultaneous groups
  (needed for real multi-hop beyond one group's members) are not implemented — see the
  "Path to multi-hop" note in `WifiDirectManager`'s class doc.
- **No persistent group / auto-reconnect.** A dropped connection requires calling
  `connectToPeer` again; there's no retry/backoff loop like the BLE tier's neighbour
  reconnect logic.
- **Not wired into the UI or the per-hop arbiter.** `WifiDirectTransport` is registered in
  `AppContainer` (so a future arbiter can pick between `Tier.INTERNET` /
  `Tier.WIFI_DIRECT` / `Tier.BLE_MESH`) but nothing currently calls `wifiDirectManager
  .start()` or `connectToPeer` from a screen — see §0.2/§3 above for the temporary hooks
  used to test it directly.
- **Not run on a device this session** — this doc is written to be followed, not a report
  of a completed run; do that pass before trusting any of the above.
