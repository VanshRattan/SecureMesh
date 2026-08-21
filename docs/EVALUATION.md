# EVALUATION.md — collecting the paper's results-section numbers

`MetricsCollector` (`data/metrics/MetricsCollector.kt`) writes one append-only CSV per app
run to `Android/data/com.capstone.chatapp/files/metrics/metrics_<yyyyMMdd_HHmmss>.csv` on
each device's own external storage. It is compiled into every build but only actually writes
anything when `BuildConfig.DEBUG` is true — a release APK is a permanent no-op. Pull the file
with `adb pull` or a file manager; there is no network upload, no server, and no PII (every id
is SHA-256-hashed before it's written — see the class doc for why that's still safely
joinable across devices).

This is deliberately a **raw event log**, not a pre-aggregated summary: every send attempt,
receive, ack and periodic battery sample is its own row. All four numbers CLAUDE.md §5.5
asks for (delivery ratio, latency, hop count, per-tier usage) are *derived* from this log —
by a spreadsheet, `pandas`, or `awk`, not by the app — because deriving them at record time
would mean keeping a live per-message aggregate in memory for the life of the session, which
is exactly the kind of state this design avoided for "minimal overhead."

---

## 1. CSV columns

| column | meaning |
|---|---|
| `timestampMs` | Wall-clock time of the event (device-local; see the clock-skew caveat in §3). |
| `eventType` | One of `SEND_ATTEMPT`, `SEND_RESULT`, `ACK_SEND_ATTEMPT`, `ACK_SENT`, `RECEIVED`, `ACK_RECEIVED`, `ENERGY_SAMPLE`. |
| `msgIdHash` | SHA-256(`Packet.msgId`), truncated to 16 hex chars. The join key across every row — and across two devices' separate CSVs — for the same message. Empty on `ENERGY_SAMPLE` rows. |
| `tier` | `INTERNET` / `WIFI_DIRECT` / `BLE_MESH`. Empty on `SEND_RESULT`/`ACK_SENT` (the *overall* outcome isn't tier-specific — see §2.4) and on `ENERGY_SAMPLE`. |
| `priority` | `NORMAL` or `EMERGENCY` (`Packet.priority` — SOS broadcasts are always `EMERGENCY`). |
| `hopCount` | `BleConstants.DEFAULT_TTL - Packet.ttl`, floored at 0; always 0 for `INTERNET` (a Firestore delivery is a direct single hop by construction, not a relay). Empty on `ENERGY_SAMPLE`. |
| `payloadBytes` | `Packet.payload.size` — ciphertext length for a 1:1 message, plaintext SOS length for a broadcast, 0 for an ack. |
| `outcome` | `SENT` / `QUEUED` / `FAILED` / `UNAVAILABLE` (from `SendResult`). Only on `SEND_ATTEMPT`/`SEND_RESULT`/`ACK_SEND_ATTEMPT`/`ACK_SENT` rows. |
| `batteryPct` | 0–100. Only on `ENERGY_SAMPLE` rows (sampled every 30s while the app is alive, from `BatteryManager`). |
| `charging` | `true`/`false`. Only on `ENERGY_SAMPLE` rows. |
| `bleNeighbors` | `BleMeshManager.status.value.neighborCount` at sample time — this device's proxy for "how much mesh relay traffic am I in range to carry." Only on `ENERGY_SAMPLE` rows. |
| `wifiDirectSockets` | `WifiDirectManager.status.value.connectedSockets` at sample time. Only on `ENERGY_SAMPLE` rows. |

**What produces each `eventType`:**
- `SEND_ATTEMPT` / `ACK_SEND_ATTEMPT` — `TransportSendCoordinator` tries one candidate tier (in the arbiter's ranked order); one row per tier actually tried, not just the winner. `ACK_` variants are the delivery-receipt packet's own send, not the message being acked.
- `SEND_RESULT` / `ACK_SENT` — one row per `TransportSendCoordinator.send()` call, the overall `SENT`/`QUEUED` result after every candidate was tried.
- `RECEIVED` — a real message (SOS broadcast or a decrypted 1:1) arriving at the device that's either the destination (`OfflineMessageRouter`) or displaying it for the first time (`EmergencyViewModel.addItem`, deduplicated by `msgId` the same way the UI itself dedupes).
- `ACK_RECEIVED` — the delivery receipt for a 1:1 message arriving back at the *original sender*. This is the one event that closes the loop entirely on one device's own CSV — see §2.2.
- `ENERGY_SAMPLE` — periodic, independent of any message.

---

## 2. Deriving the four target metrics

### 2.1 Per-tier usage
Group `SEND_ATTEMPT` rows (and `RECEIVED` rows, for the receiving side) by `tier`, filtered to
`outcome == "SENT"`. Compare counts across `INTERNET` / `WIFI_DIRECT` / `BLE_MESH` to see which
tier actually carried traffic in a given scenario — not just which was *available*
(`TransportArbiter`'s reachability score), but which one `TransportSendCoordinator` picked and
succeeded on.

### 2.2 Delivery ratio and latency — targeted (1:1) messages
Because `OfflineMessageRouter` always sends a `Packet.ack` back to the original sender, **both
numbers are derivable from the sender's own CSV alone, no cross-device join needed**:

- For each distinct `msgIdHash` with a `SEND_RESULT` row (a message this device tried to
  send): **delivered** if that same `msgIdHash` also has an `ACK_RECEIVED` row anywhere later
  in the log; otherwise it never got a receipt.
- `delivery ratio = (distinct msgIdHash with an ACK_RECEIVED row) / (distinct msgIdHash with a SEND_RESULT row)`
- `latency(msgIdHash) = timestampMs(first ACK_RECEIVED) - timestampMs(first SEND_RESULT)`

### 2.3 Delivery ratio and latency — broadcasts (SOS)
An SOS has no ack (public-broadcast-by-design, see CLAUDE.md §4) and no single recipient, so
this direction **requires pooling the CSVs from every phone in the test** and joining on
`msgIdHash`:

- `delivered` if the sender's `msgIdHash` (from its `SEND_RESULT` row, `priority == EMERGENCY`)
  appears as a `RECEIVED` row on at least one *other* phone's CSV.
- `delivery ratio = (distinct EMERGENCY msgIdHash received by ≥1 other phone) / (distinct EMERGENCY msgIdHash sent)`
- `latency(msgIdHash, receiver) = timestampMs(RECEIVED on receiver's CSV) - timestampMs(SEND_RESULT on sender's CSV)`

**Clock-skew caveat**: `timestampMs` is each device's own wall clock — there's no NTP
sync step in the app. Cross-device latency numbers are only meaningful if the test phones'
clocks were manually synced (or corrected for) before the run; same-device numbers (§2.2) have
no such problem since both timestamps come from one clock.

### 2.4 Hop count
Read `hopCount` directly off the `RECEIVED`/`ACK_RECEIVED` row for a given `msgIdHash` — no
derivation needed, it's already `DEFAULT_TTL - ttl` at arrival time (§1). For `tiersUsed`
per message (how the arbiter's choice evolved across retries — e.g. queued on `BLE_MESH`, then
re-sent over `INTERNET` once connectivity returned), group `SEND_ATTEMPT` rows by `msgIdHash`
and list `tier` values in row order.

### 2.5 Handover time
There's no literal mid-flight handover in this architecture — a message that can't go out on
any tier right now is queued (`StoreCarryForwardQueue`) and re-attempted as a **new**
`SEND_ATTEMPT`/`SEND_RESULT` pair (same `msgIdHash`) the next time
`TransportArbiter.availabilityChanges` fires. So "handover time" is measured as: how long a
message sat queued before a bearer's availability change let it go out —

`handover time(msgIdHash) = timestampMs(first SEND_RESULT with outcome=SENT) - timestampMs(first SEND_RESULT with outcome=QUEUED)`

— for any `msgIdHash` whose first `SEND_RESULT` was `QUEUED`. Zero (or absent) rows mean the
message sent on the very first attempt, no handover needed.

### 2.6 Energy cost of relaying
No per-message energy accounting exists (neither `BleMeshManager` nor `WifiDirectManager`
exposes real per-packet power draw, and instrumenting that would mean touching the mesh's most
hardened, still-never-run-on-device code — see CLAUDE.md §6). Instead, correlate
`ENERGY_SAMPLE` rows over a session: `batteryPct` decline over wall-clock time, split by
whether `bleNeighbors`/`wifiDirectSockets` was elevated (busy relaying for others) vs. near
zero (idle mesh) for the same `charging=false` stretches. This is an approximation, not a
per-message cost — documented honestly rather than claiming precision the instrumentation
doesn't have.

---

## 3. The three evaluation scenarios

Each needs the two/three-phone setup from `docs/RUNTIME_TEST.md`/`docs/WIFIDIRECT_TEST.md`
(same physical prerequisites — BLE peripheral-mode support, phones in range) plus: enable
debug builds (`BuildConfig.DEBUG` — i.e. install the normal `:app:assembleDebug` output, not a
release build) on every phone, and pull each phone's `metrics/*.csv` via `adb pull
/sdcard/Android/data/com.capstone.chatapp/files/metrics/ .` after the run.

### 3.1 Dead-internet
Airplane mode (or just Wi-Fi + mobile data off) on every test phone before sending anything.
`NetworkMonitor.currentlyOnline()` should read false, so `TransportArbiter` excludes `INTERNET`
outright (`reachabilityScore` returns 0). Send both an SOS and a 1:1 message; expect every
`SEND_ATTEMPT` row's `tier` to be `WIFI_DIRECT`/`BLE_MESH` only, and (for 1:1) an
`ACK_RECEIVED` to still close the loop purely over BLE/Wi-Fi Direct — this is the scenario that
validates offline 1:1 messaging (Session 9) actually works end to end, not just that packets
leave the sender.

### 3.2 Congested venue
Five or more phones in BLE range simultaneously, all sending. Stresses
`BleConstants.MAX_CONCURRENT_GATT_CONNECTIONS` (the bounded GATT client pool) and the retry
backoff — watch for `BleLog`'s `QUEUE_FULL`/`RETRY_SCHEDULE` lines alongside the CSV's
`hopCount` distribution (a dense room should show more multi-hop `RECEIVED` rows than a
two-phone test) and any drop in delivery ratio (§2.2/§2.3) compared to the same message count
in a quiet room. `bleNeighbors` in the `ENERGY_SAMPLE` rows is the per-device congestion
signal to plot alongside it.

### 3.3 Censored
Internet reachable at the OS/interface level (so `NetworkMonitor.currentlyOnline()` — which
validates actual connectivity, not just "a network interface is up" — may still read true) but
the specific endpoints Firestore needs are blocked (a firewalled Wi-Fi network, a captive
portal, or a VPN/proxy configured to drop Firestore's hosts). Expect `SEND_ATTEMPT` rows with
`tier=INTERNET` and `outcome=FAILED`, followed by a same-`msgIdHash` `SEND_ATTEMPT` on
`WIFI_DIRECT`/`BLE_MESH` that succeeds — this is the scenario that best demonstrates
relay-blindness mattering in practice (CLAUDE.md §5.2): the fallback tiers never had a
plaintext copy to censor in the first place for a 1:1 message, and the SOS broadcast reaching
peers over BLE/Wi-Fi Direct doesn't depend on the blocked path at all.

**Known instrumentation gap, flagged honestly**: `TransportArbiter` has no signal today for
"the OS says I'm online but Firestore specifically is unreachable" *before* attempting a send
— it only finds out via `SEND_ATTEMPT`'s `FAILED` outcome, same as this scenario's own
detection method above. A tighter arbiter (e.g. probing Firestore reachability directly,
not just `NetworkMonitor`'s general connectivity check) is future work, not something this
instrumentation pass changed.
