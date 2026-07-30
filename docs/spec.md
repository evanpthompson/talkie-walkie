# Talkie-Walkie — Spec

This is the source-of-truth spec: Part 1 formalizes the system as it exists
today (v1, shipped — see `README.md` for the user-facing feature tour and
setup instructions). Part 2 is the roadmap, starting with the next phase:
reliability hardening.

---

## Part 1 — Current system (v1)

### Purpose

A native Android app that turns a set of phones into a push-to-talk radio
net over Bluetooth Classic, with no infrastructure (no internet, no server,
no pairing service) beyond OS-level Bluetooth pairing between the devices
themselves. Primary use case: group riding (motorcycle/bicycle/etc.) where
a hands-free, phone-carrier-independent PTT channel is needed.

### Functional requirements (as built)

1. One device can **host** a channel (`Role.HUB`); others **join** it
   (`Role.CLIENT`). A channel is identified by a name the user chooses; the
   name deterministically derives a `UUID` (`ChannelManager.channelUuid`)
   that both sides compute independently — no registry or coordination
   server.
2. A client can find an active hub without knowing the channel name in
   advance, by scanning bonded devices for one advertising via SDP
   (`ChannelScanner` / `ChannelDiscovery`), or by typing the name manually.
3. Only one participant transmits at a time. A transmit attempt while
   another participant holds the floor is rejected (`Frame.Blocked`) and
   the requester's UI shows **CHANNEL BUSY**.
4. Transmission can be started via touch (hold PTT button), the physical
   Volume Up key (while the app is foregrounded), or the persistent
   notification's **Transmit**/**Stop** action (works from the lock screen,
   does not require the app to be foregrounded).
5. Audio is captured at 16 kHz mono, Opus-compressed (~80 bytes per 40 ms
   frame vs. 1280 bytes raw — ~16×), and relayed hub → all other clients,
   or client → hub → all other clients. Round-trip is store-and-forward at
   the hub, not device-to-device.
6. A transmission auto-releases after 30 seconds, with a 5-second countdown
   warning (haptic pulses, UI countdown) before cutoff — prevents a stuck
   or forgotten PTT from locking the channel indefinitely.
7. Silence is not transmitted: an RMS squelch gate (threshold 300.0, 6-frame
   /240 ms hold) suppresses dead air within an open transmission without
   clipping word endings.
8. **Riding mode**: continuous mic listening for a wake word ("Porcupine",
   Picovoice on-device detection), followed by Android `SpeechRecognizer`
   STT, followed by Gemini 1.5 Flash function-calling to parse one of six
   commands (create channel, join channel, start/stop transmitting,
   disconnect, toggle riding mode) — hands-free control without touching
   the screen.
9. Last channel name + role persist across launches (`ChannelPrefs`) for
   one-tap rejoin.
10. Client-side auto-reconnect on drop: exponential backoff, 2s → 30s cap,
    10 attempts, then gives up and returns to `Disconnected`.
11. A `PARTIAL_WAKE_LOCK` is held for the entire time a device has an
    active role (hub or client, including `Searching`/`Reconnecting`), and
    `AUDIOFOCUS_GAIN` is requested for the same span — any focus loss
    (call, another app's audio) force-stops an in-progress transmission.

### Non-goals (v1)

- **No mesh / multi-hop.** Topology is strictly star: one hub, N clients,
  all traffic passes through the hub. A client cannot hear another client
  directly, and range is bounded by each client's individual link to the
  hub, not the group's combined span.
- **No hub migration / failover.** If the hub leaves or its process dies,
  the channel ends for everyone; there is no election of a new hub or
  automatic re-hosting. Clients retry connecting to the *same* hub UUID
  until they exhaust reconnect attempts, then give up.
- **No app-level authentication.** Anyone whose device is OS-level Bluetooth
  *paired* with the hub (or with another client, for discovery scanning)
  and knows or discovers the channel name can join. Security boundary is
  Bluetooth pairing, not anything the app adds.
- **No end-to-end encryption beyond what Bluetooth Classic itself
  provides.** No app-layer payload encryption.
- **No BLE.** RFCOMM over Bluetooth Classic only; this bounds concurrent
  connection count and range to what Classic BT supports on a given device.
- **No cross-platform client.** Android only (min API 26).

### Topology & roles

```
        ┌─────────┐
        │   Hub    │  Role.HUB — one BluetoothServerSocket,
        │ (device) │  one RFCOMM connection per client
        └────┬────┘
      ┌───────┼───────┐
      │       │       │
  ┌───┴──┐┌───┴──┐┌───┴──┐
  │Client││Client││Client│  Role.CLIENT — one RFCOMM socket to hub
  └──────┘└──────┘└──────┘
```

- The hub is also a full participant: it can transmit and receive, and its
  own name is included in `memberNames`.
- `HalfDuplexLock` (an `AtomicReference<String?>` with compare-and-set) is
  owned by the hub and is the single source of truth for who may transmit.
  A client's local `isBlocked` flag is advisory UI state driven by
  `Frame.Blocked`/`Frame.Free`, not the lock itself.
- Clients are keyed on the hub by a stable id (`ConnectedClient.id`, the
  remote device's Bluetooth MAC address from `socket.remoteDevice.address`)
  in a `ConcurrentHashMap<String, ConnectedClient>`. Device name
  (`ConnectedClient.name`, from the client's `Hello` frame) is display-only
  and does not need to be unique — two clients sharing a name no longer
  collide in the map or in half-duplex lock ownership.

### Connection state machine

`ConnectionState` (`model/WalkieState.kt`):

| State | Meaning | `isActive` |
|---|---|---|
| `Disconnected` | No role, no channel | false |
| `Hosting` | Hub, server socket open | true |
| `Searching` | Client, first connection attempt in progress | false |
| `Connected(deviceName)` | Client, RFCOMM link up to `deviceName` | true |
| `Reconnecting(attempt)` | Client, link dropped, backoff in progress | false |

Client-side transition driver is `manageClientChannel()`
(`WalkieTalkieService.kt`): `Searching` → connect attempt → on success
`Connected` (and `attempt` resets to 0) → suspends on `mgr.connected.first
{ !it }` (blocks until the socket drops) → `Reconnecting(1)` → loop, with
delay `min(2000 * 2^(attempt-1), 30000)` ms between attempts, up to 10
attempts, after which role resets to `NONE` and state returns to
`Disconnected`.

### Channel discovery protocol (SDP)

```
Hub (ChannelDiscovery)                 Client (ChannelScanner)
──────────────────────                 ──────────────────────
listenUsingInsecureRfcomm              for each bonded device (parallel):
  (DISCOVERY_UUID)          ←connect─    createInsecureRfcomm(DISCOVERY_UUID)
                            ←──────── socket.connect()
write(channelName UTF-8)   ─────────→
close()                               read all bytes → FoundChannel(name, deviceName)
```

`DISCOVERY_UUID = UUID.nameUUIDFromBytes("tw.discovery".toByteArray(UTF-8))`
— fixed and identical across all installs; only the channel *name* payload
varies. A device only responds on this socket while it is actively hosting
(`ChannelDiscovery.start()` is called from `createChannel()`, stopped in
`leaveChannel()`/`onDestroy()`).

### Frame protocol (PTT channel, per-connection)

Binary frames over RFCOMM: `[TYPE : 1 byte][LENGTH : 2 bytes big-endian][PAYLOAD : N bytes]`

| Type | Byte | Payload | Direction |
|---|---|---|---|
| `Audio` | `0x01` | Opus-encoded PCM | client→hub, hub→client(s) |
| `Busy` | `0x02` | _(empty)_ | client→hub — "I am starting to transmit" |
| `Free` | `0x03` | _(empty)_ | client→hub — "I have stopped transmitting" |
| `Hello` | `0x04` | Device name (UTF-8) | client→hub, first frame after connect |
| `Roster` | `0x05` | Newline-delimited member names (UTF-8) | hub→client(s) |
| `Blocked` | `0x06` | _(empty)_ | hub→client — transmit request denied |

`FrameCodec` decode returns `null` on stream close/truncation, which both
`HubConnectionManager.handleClient` and the client inbound loop treat as
"connection ended."

### Channel UUID derivation

```kotlin
UUID.nameUUIDFromBytes("tw.channel.$name".toByteArray(Charsets.UTF_8))
```

Deterministic, so hub and client compute the same UUID independently from
the same channel-name string — no coordination needed. Name is
case-sensitive (covered by `ChannelManagerTest`).

### Audio pipeline

```
Mic → AudioEngine.capturedAudio (SharedFlow<ByteArray>)
        ├── isTransmitting  → update audioLevel (VU TX)
        │                   → SquelchGate.shouldTransmit()
        │                   → OpusCodec.encode() → Hub/ClientConnectionManager.sendAudio()
        └── ridingMode      → WakeWordDetector.feedAudio()
                                  └── on detection → stop capture → STT → Gemini
                                                    → executeCommand()
                                                    → restart capture

Inbound Opus packet → OpusCodec.decode() → update audioLevel (VU RX)
                                          → schedule 120 ms decay to 0
                                          → AudioEngine.playFrame() → speaker
```

Capture and wake-word listening share one `AudioRecord` stream via
`ensureCaptureObserver()` — riding mode does not open a second mic session,
it reads from the same `capturedAudio` flow PTT does.

### Key constants

| Constant | Value | Source |
|---|---|---|
| `SAMPLE_RATE` | 16 000 Hz | `AudioEngine` |
| `FRAME_BYTES` | 1 280 bytes (40 ms mono 16-bit PCM) | `OpusCodec` |
| Opus frame / bitrate | 640 samples / 16 000 bps | `OpusCodec` |
| `TX_TIMEOUT_SECS` / `TX_WARNING_SECS` | 30 s / 5 s | `WalkieTalkieService` |
| Squelch threshold / hold | 300.0 RMS / 6 frames (240 ms) | `SquelchGate` |
| RX level decay | 120 ms | `WalkieTalkieService.playRxAudio` |
| Reconnect backoff | 2 s → 30 s cap, 10 attempts | `manageClientChannel` |
| Discovery UUID | `nameUUIDFromBytes("tw.discovery")` | `ChannelManager` |

### Permissions (`AndroidManifest.xml`)

`BLUETOOTH`/`BLUETOOTH_ADMIN` (≤ API 30), `BLUETOOTH_CONNECT` +
`BLUETOOTH_SCAN` (`neverForLocation`) (API 31+), `INTERNET` (Gemini only),
`RECORD_AUDIO`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE`,
`VIBRATE`, `WAKE_LOCK`. `foregroundServiceType="microphone"` only — does
**not** declare `connectedDevice`, even though the service's core job is a
Bluetooth connection. No battery-optimization-exemption request
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) anywhere in the app.

### Known limitations of v1 (carried into Part 2 as concrete gaps)

- Hub failure/departure has no recovery path — full channel loss.
- 10-attempt reconnect exhaustion is silent beyond the state label; no
  distinct "give up" notification or user-facing explanation.
- No `connectedDevice` foreground service type declared.
- No battery-optimization exemption requested — OEM battery managers
  (Samsung, Xiaomi, etc.) can kill the service despite the wake lock.
- No instrumented (on-device, cross-device) test coverage — all 8 existing
  test files are local JVM/Robolectric unit tests of isolated components
  (codec round-trips, UUID derivation, lock contention, prefs, timeout
  timing). Nothing exercises two real devices talking over actual RFCOMM.
- No field data: no logging/telemetry of reconnect frequency, drop causes,
  or battery drain from real rides.

---

## Part 2 — Roadmap

### Phase 2 — Reliability hardening

**Goal:** make what's already built dependable on a real ride — long
sessions, screen off, phone in a pocket or mount, OEM battery managers
active — before adding new topology or radio capabilities. No new
features, no BLE, no mesh in this phase.

**Requirements:**

1. ~~**Duplicate-name collision fix.**~~ Done — `HubConnectionManager` now
   keys clients by `BluetoothDevice.address` (`ConnectedClient.id`); device
   name is display-only (`ConnectedClient.name`, from `Hello`) and no
   longer participates in the clients map key or half-duplex lock token.
2. **Explicit give-up signal.** When `manageClientChannel` exhausts its 10
   reconnect attempts, surface a distinguishable state/notification (not
   just falling silently back to `Disconnected`) so the rider knows to
   manually re-host or re-join rather than assuming the app is still
   trying.
3. **Foreground service type.** Add `connectedDevice` alongside
   `microphone` in `foregroundServiceType`, matching what the service
   actually does.
4. **Battery-optimization exemption.** Request
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (or point the user to the
   OEM-specific equivalent, e.g. Samsung's "unmonitored app" list) at
   first channel creation/join, since a `PARTIAL_WAKE_LOCK` alone doesn't
   protect against aggressive OEM process killing.
5. **Instrumented connectivity test.** At least one Android instrumented
   test (`androidTest`) that pairs two emulators/devices over RFCOMM and
   exercises create → join → transmit → drop → reconnect, since none of
   the current 8 unit tests touch real Bluetooth I/O.
6. **Field logging.** Lightweight local logging (rotating file or
   `Log.i` with a filterable tag) of connection state transitions,
   reconnect attempts/outcomes, and audio focus loss events — enough to
   diagnose a bad ride after the fact without a debugger attached.
7. **Reconnect UX clarity.** Today `Reconnecting(attempt)` is the only
   signal; decide whether the 30s-cap/10-attempt policy (worst case ~4.5
   minutes of silent retrying before giving up) is right for the actual
   ride use case, or should be tunable/shorter with a manual "keep
   trying" option.

**Open questions (need your input before implementation):**

- Is losing the hub *acceptable* (whoever hosts must stay in range/on) or
  should hub failover be pulled forward into this phase rather than
  deferred to Phase 3? (Kept out for now per your call, but the
  10-attempt/no-recovery behavior above is a direct consequence of no
  failover, so worth confirming that's still fine.)
- What counts as "reliable enough to ship"? A target like "survives a
  2-hour ride with screen off, phone in jacket pocket, one reconnect
  event recovered automatically" would give this phase a concrete exit
  condition.
- Real-device access: instrumented tests and field logging both need
  actual rides/hardware to validate, not just emulators. Do you have two+
  Android 8+ devices to test with, or does this phase need to lean more on
  emulator-only validation first?

### Phase 3 (parked, not scoped yet)

Ideas raised but deliberately not detailed here — revisit only after
Phase 2's exit condition is met:

- **BLE fallback/hybrid mode** — range and battery tradeoffs vs. Classic
  RFCOMM; would need its own protocol design (Classic and BLE are not
  interchangeable at the socket level).
- **Multi-hop/mesh** — moving beyond star topology so range extends
  device-to-device; a materially larger redesign (routing, loop
  avoidance, no single half-duplex-lock owner) than anything in Phase 2.
