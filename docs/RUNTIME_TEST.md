# RUNTIME_TEST.md — verifying the offline SOS mesh on two real phones

The BLE mesh cannot be tested on an emulator: Android emulators have no BLE radio and
cannot advertise. Everything below needs **two physical Android phones**.

Every mesh step logs under a single logcat tag, **`SafeSphereBLE`**, so one filter follows
the whole test.

---

## 0. Before you start

### 0.1 Firebase config (required — the repo does not contain it)

`app/google-services.json` is gitignored and **not** in the repo. Without it the build
fails immediately with `File google-services.json is missing`. Download it from the
Firebase console (Project settings → Your apps → Android app `com.capstone.chatapp`) and
drop it in `app/`.

Also confirm the console-side setup in `SESSION_CONTEXT.md` §7 is done: Email/Password
auth enabled, Firestore created, rules published, and the `chats` composite index built.
Sign-up in step 2 fails without these.

### 0.2 Build and install

There is still no `./gradlew` wrapper in this repo. Either open the project in Android
Studio and hit Run, or use the cached Gradle 8.5 distribution:

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

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Install on **both** phones:

```bash
adb devices                                  # note both serials
adb -s <SERIAL_A> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <SERIAL_B> install -r app/build/outputs/apk/debug/app-debug.apk
```

### 0.3 Phone requirements

- Android 6.0 (API 23) or newer. The app's `minSdk` is 24.
- Both phones must support **BLE peripheral mode** (advertising). Most phones from 2016
  on do; some budget devices do not. The app tells you: if a phone cannot advertise, the
  Emergency banner reads *"…this phone cannot advertise, so others cannot reach it"* and
  the log shows `ADV_FAIL | error=FEATURE_UNSUPPORTED`. Such a phone can still **send**
  an SOS but can never **receive** one. Swap it out before blaming the code.
- Keep the phones within a few metres of each other.

---

## 1. Open the log stream (do this first, in two terminals)

```bash
# Terminal 1 — sender
adb -s <SERIAL_A> logcat -c && adb -s <SERIAL_A> logcat -s SafeSphereBLE:V

# Terminal 2 — receiver
adb -s <SERIAL_B> logcat -c && adb -s <SERIAL_B> logcat -s SafeSphereBLE:V
```

`logcat -c` clears the buffer so you only see this run. `:V` is important — the MTU,
queue and scan-result lines are logged at DEBUG level.

Every line is `STEP | key=value key=value`, so you can narrow further:

```bash
adb -s <SERIAL_B> logcat -s SafeSphereBLE:V | grep -E "RX_PACKET|RELAY_SEND|DEDUP_DROP"
```

MAC addresses are shortened to their last five characters (`..:1A:2B`) and message ids to
their first eight, which is enough to match a message across both phones.

---

## 2. Sign up two accounts (needs internet)

Both phones must be **online** for this step — accounts live in Firebase Auth.

1. Phone A: open the app → **Sign up** → e.g. `alice@test.com` / `Passw0rd!` → username `Alice`.
2. Phone B: same, `bob@test.com` / `Passw0rd!` → username `Bob`.
3. Optional sanity check that Firebase works at all: from Home tap the search icon
   (Discover → All Users), tap the other person, send a message, confirm it arrives.

The username matters: it is what the other phone shows as the SOS sender, and it is what
is served over the BLE identity characteristic.

---

## 3. Start the mesh on both phones

On **each** phone: Home → the red **SOS** button → **Emergency Mode**.

The screen asks for what it needs, in this order, and each prompt maps to one blocker:

| Prompt | Why |
|---|---|
| "Nearby devices" permission | `BLUETOOTH_SCAN` / `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` (Android 12+) |
| Notifications | Android 13+, only for the ongoing service notification. **Declining is fine** — the mesh still runs |
| "Allow app to turn on Bluetooth?" | Bluetooth was off |
| Location settings | Android 11 and below only: BLE scanning returns nothing with the location toggle off, even with the permission granted |

Grant them. You should get an ongoing **"Emergency mesh active"** notification.

**Both phones must have Emergency Mode opened at least once.** The mesh only runs once
the foreground service has started; a phone that never opened the screen cannot receive
an SOS. Once started, the service keeps it running in the background and with the screen
off.

### Expected log on startup (both phones)

```
MESH_START      | selfId=Qk3vR8sd selfName=Alice sdk=34 device=Google Pixel 7
PRECHECK        | ok=true advertiseSupported=true
GATT_SERVER_OPEN| service=0000c0de-0000-1000-8000-00805f9b34fb
ADV_START       | attempt=1 mode=LOW_LATENCY connectable=true
SCAN_START      | filter=0000c0de-0000-1000-8000-00805f9b34fb mode=LOW_LATENCY
GATT_SERVER_READY | service=0000c0de-0000-1000-8000-00805f9b34fb
ADV_OK          | txPower=3 mode=2
MESH_START      | running=true server=true scanning=true
SERVICE         | foreground=true type=connectedDevice
```

Then, within a few seconds, each phone discovers the other:

```
NEIGHBOR_ADD    | peer=..:1A:2B rssi=-54 total=1
IDENTITY_REQ    | peer=..:1A:2B
TX_CONNECT      | peer=..:1A:2B ...
IDENTITY_SERVED | peer=..:9F:0C offset=0 bytes=41      <- logged on the OTHER phone
IDENTITY_OK     | peer=..:1A:2B uid=8fTt2ZqA name=Bob
```

The Emergency top bar chip should now read **"… · 1 near"**. `IDENTITY_OK` is also what
populates Discover → Nearby.

**If you never see `NEIGHBOR_ADD`,** the two phones cannot see each other. Check
`ADV_OK` appears on the *other* phone — a phone that never logs `ADV_OK` is invisible.

---

## 4. Go offline

On **both** phones turn off Wi-Fi and mobile data. Leave **Bluetooth on**.

- Airplane mode also turns Bluetooth off on most phones. If you use airplane mode, turn
  Bluetooth back on afterwards — the app notices and restarts the radio:
  `BT_STATE | state=ON action=restarting radio`.
- The Emergency chip should switch to **"Offline · BLE"** and the banner to
  *"No internet — SOS will be sent over Bluetooth to nearby phones (1 found)."*

---

## 5. Send the SOS

On **phone A**: type a message in Emergency Mode and tap **🆘 SEND SOS**.

### Expected log on the sender (A)

```
SOS_SEND   | source=ui msgId=1a2b3c4d online=false meshRunning=true neighbors=1
SOS_SEND   | msgId=1a2b3c4d ttl=10 neighbors=1 chars=11
TX_QUEUE   | msgId=1a2b3c4d queued=1 neighbors=1 excluded=-
TX_CONNECT | peer=..:1A:2B msgId=1a2b3c4d bytes=97
TX_MTU     | side=client peer=..:1A:2B mtu=185 status=SUCCESS payload=97
TX_WRITE   | peer=..:1A:2B msgId=1a2b3c4d
TX_OK      | peer=..:1A:2B msgId=1a2b3c4d
```

`TX_OK` is the delivery confirmation: the bytes were written into the other phone's
characteristic and acknowledged.

### Expected log on the receiver (B)

```
GATT_SERVER_CONN | peer=..:9F:0C state=CONNECTED status=SUCCESS
TX_MTU           | side=server peer=..:9F:0C mtu=185
RX_PACKET        | msgId=1a2b3c4d from=..:9F:0C sender=Alice hops=0 ttl=10 reach=1 chars=11
RELAY_SEND       | msgId=1a2b3c4d ttl=10->9 hops=0->1 exclude=..:9F:0C
TX_QUEUE         | msgId=1a2b3c4d queued=0 neighbors=1 excluded=..:9F:0C
GATT_SERVER_CONN | peer=..:9F:0C state=DISCONNECTED status=SUCCESS
```

`queued=0` is correct with only two phones: the one neighbour B has is A, and A is
excluded because the packet came from there.

### Expected on screen (B)

A red 🆘 bubble on the **left** reading `🆘 Alice` with the message and a timestamp.
On A the same message appears on the **right** as `🆘 You`.

**This is the pass condition for the offline SOS test.**

---

## 6. Confirm de-duplication and relay (needs a third phone, optional)

With three phones A–B–C in a line:

- C receives via B and logs `RX_PACKET | hops=1`, and the bubble shows *"relayed 1 hop"*.
- If C is also in range of A directly, the second copy is dropped:
  `DEDUP_DROP | msgId=1a2b3c4d from=..:1A:2B hops=1` — first arrival wins, so the
  shortest path is the one displayed.
- TTL decrements once per hop: `ttl=10->9`, then `9->8`, and so on. When it reaches 1 the
  packet stops: `TTL_DROP | reason=ttl exhausted`.

---

## 7. Confirm persistence and background operation

1. With B still offline, force-close and reopen the app on B → the SOS is still listed
   (it is persisted in DataStore, not Firestore).
2. Lock phone B's screen, send another SOS from A, then unlock B → the message is there.
   The foreground service keeps the mesh alive.
3. Bring both phones back online → the message list does not duplicate, because the BLE
   copy and the Firestore copy share the same `msgId`.

---

## 8. Reading the logs: what each step means

| Step | Meaning |
|---|---|
| `MESH_START` / `MESH_STOP` | Mesh lifecycle. The second `MESH_START` line reports whether it actually came up |
| `PRECHECK` | Adapter, permissions, Bluetooth state and (below API 31) the location toggle |
| `PERMISSION` | Which runtime permissions are missing or were denied |
| `ADV_START` / `ADV_OK` / `ADV_FAIL` / `ADV_RESTART` | Advertiser. No `ADV_OK` = this phone is undiscoverable |
| `GATT_SERVER_OPEN` / `GATT_SERVER_READY` / `GATT_SERVER_FAIL` | The inbound side. `READY` means the service and its characteristics are registered |
| `GATT_SERVER_CONN` | A peer connected to us to write a packet |
| `SCAN_START` / `SCAN_RESULT` / `SCAN_FAIL` | Scanner. `SCAN_RESULT` is throttled to one line per device per 10 s |
| `NEIGHBOR_ADD` / `NEIGHBOR_DROP` | The flood set. Drops are logged with a reason (stale, write failures) |
| `IDENTITY_REQ` / `IDENTITY_OK` / `IDENTITY_FAIL` | Reading a peer's `uid|name`; `IDENTITY_OK` fills Discover → Nearby |
| `IDENTITY_SERVED` | We answered someone else's identity read |
| `SOS_SEND` | A broadcast originated here (logged twice: once from the UI, once from the mesh) |
| `TX_QUEUE` | How many neighbours the packet was queued to |
| `TX_CONNECT` / `TX_MTU` / `TX_WRITE` / `TX_OK` / `TX_FAIL` | One outbound GATT write, start to finish |
| `RX_PACKET` | A packet arrived. Carries `hops`, `ttl` and `reach` |
| `RX_BAD` | Bytes arrived that did not parse as a packet |
| `DEDUP_DROP` | Already-seen `msgId` — a longer path arriving late |
| `RELAY_SEND` | Re-broadcasting, with the TTL decrement shown as `ttl=10->9` |
| `TTL_DROP` | Not relayed: TTL exhausted or reach cap hit |
| `OUTBOX` | A queued broadcast was replayed to a phone we just met |
| `BT_STATE` | The user toggled Bluetooth; the radio is torn down or restarted |
| `SERVICE` | Foreground service start/stop, including failures to enter the foreground |

---

## 9. Troubleshooting by log line

| What you see | What it means | Fix |
|---|---|---|
| `MESH_START \| aborted=Missing permission: BLUETOOTH_SCAN…` | Runtime permission denied | App info → Permissions → Nearby devices → Allow |
| `MESH_START \| aborted=Bluetooth is off` | Adapter off | Turn Bluetooth on; the screen also prompts on resume |
| `MESH_START \| aborted=Turn on Location…` | Android ≤ 11 with the location toggle off | Turn on Location. Scans return **nothing** otherwise |
| `ADV_FAIL \| error=FEATURE_UNSUPPORTED` | Chipset has no BLE peripheral role | Use a different phone as the receiver |
| `ADV_FAIL \| error=DATA_TOO_LARGE` | Advertisement over 31 bytes | Should not happen (the device name is excluded); report it |
| `ADV_FAIL \| error=TOO_MANY_ADVERTISERS` | Another app holds the advertisers | Retried automatically up to 4 times; close other BLE apps |
| `SCAN_FAIL \| error=SCANNING_TOO_FREQUENTLY` | Android's 5-starts-per-30-s throttle | Retried automatically after 35 s; stop restarting the app so fast |
| `SCAN_FAIL \| error=APP_REGISTRATION_FAILED` | Stale scan client in the BT stack | Toggle Bluetooth off and on |
| No `NEIGHBOR_ADD` on either phone | Nobody is advertising, or they are out of range | Confirm `ADV_OK` on the other phone; move them closer |
| `TX_FAIL \| stage=connect status=GATT_ERROR(133)` | Classic Android connect failure | Retried; if constant, toggle Bluetooth on both phones |
| `TX_FAIL \| stage=timeout` | Peer stopped responding | Neighbour is dropped after 3 failures and re-added on the next scan |
| `SOS_SEND \| neighbors=0 note=held in outbox…` | Nothing discovered yet | Expected. The packet is delivered automatically when a phone appears (`OUTBOX \| replayed=1`) |
| `SERVICE \| foreground=false` | `startForeground` was rejected | Usually Bluetooth permissions missing (Android 14) or the app was backgrounded (Android 12+). Open Emergency Mode with the app in the foreground |
| `RX_BAD` | Corrupt or truncated write | Check `TX_MTU` on the sender — a tiny negotiated MTU with a long message |

---

## 10. What this test does **not** cover

- Range and multi-hop behaviour beyond three phones.
- Battery drain over a long session (the scan runs in `LOW_LATENCY` mode).
- Offline 1:1 messaging — tapping a person in Discover/Nearby still opens the **online**
  Firestore chat. Only the SOS broadcast travels over BLE.
- Encryption. SOS is a public broadcast and is sent in plaintext by design.
