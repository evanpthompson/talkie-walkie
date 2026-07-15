# Talkie-Walkie

Native Android Bluetooth walkie-talkie — push-to-talk over Classic Bluetooth RFCOMM with voice-command riding mode. Built in Kotlin with Jetpack Compose.

Requires Android 8.0+ (API 26). No internet required for PTT; internet is only used for Gemini voice command parsing.

---

## Features

| Feature | Details |
|---|---|
| **Star topology** | One hub device accepts N clients over Bluetooth Classic |
| **Half-duplex lock** | Only one device transmits at a time; others receive BLOCKED |
| **Opus compression** | ~80 bytes per 40 ms frame vs 1280 bytes raw PCM (16× reduction) |
| **Auto-reconnect** | Exponential backoff on drop (2 s → 30 s cap, 10 attempts) |
| **PTT — touch** | Hold the on-screen button |
| **PTT — volume key** | Hold Vol ↑ when a channel is active |
| **PTT — notification** | Tap Transmit / Stop from the notification shade or lock screen |
| **Riding mode** | Wake word → speech → Gemini parses the command hands-free |
| **Speaker routing** | Toggle earpiece ↔ loudspeaker |

---

## Setup

### 1. API keys

**Porcupine** (wake word, free tier):
1. Create a free account at [console.picovoice.ai](https://console.picovoice.ai)
2. Copy your Access Key from the dashboard

**Gemini** (voice command parsing, free tier):
1. Go to [aistudio.google.com](https://aistudio.google.com)
2. Create an API key

Paste both into `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "PORCUPINE_ACCESS_KEY", "\"YOUR_KEY_HERE\"")
buildConfigField("String", "GEMINI_API_KEY",        "\"YOUR_KEY_HERE\"")
```

### 2. Pair devices

Before launching the app, pair all devices with each other via Android **Settings → Bluetooth**. The app uses Bluetooth Classic RFCOMM; pairing must exist before joining a channel.

### 3. Build and run

Open in Android Studio (Electric Eel or later), sync Gradle, and run on two or more Android 8+ devices.

---

## Usage

### Starting a channel

1. Launch on the **hub device** (the one all others will connect to).
2. Type a channel name and tap **Create Channel (Host)**.
3. The hub begins advertising the channel UUID derived from that name.

### Joining a channel

1. Launch on each **client device**.
2. Type the **same channel name** and tap **Join Channel**.
3. The app walks bonded devices looking for the hub; on success the member roster appears.

> If the hub is not found, the app retries automatically with exponential backoff and shows "Reconnecting… (attempt N)" in the status card.

### Push to talk

Three ways to transmit, all interchangeable:

| Method | How |
|---|---|
| **Touch** | Press and hold the circular PTT button |
| **Volume Up** | Hold the physical Vol ↑ key while the app is in the foreground |
| **Notification** | Tap **Transmit** in the persistent notification; tap **Stop** to release |

If someone else is already transmitting, the button shows **CHANNEL BUSY** and your transmission is rejected until they release.

### Riding mode

Riding mode keeps the microphone open continuously so you can control the app by voice without touching the screen. Enable it with the **Riding mode** toggle.

1. Say **"Porcupine"** (the built-in wake word) — the app responds with a listening indicator.
2. Speak a command.
3. Gemini parses the command and executes it.

Supported voice commands:

| What to say | Action |
|---|---|
| "Create a channel called riders" | Creates and hosts a channel named "riders" |
| "Join the channel riders" | Joins the channel named "riders" |
| "Start transmitting" | Opens PTT for 3 seconds |
| "Stop transmitting" | Releases PTT |
| "Disconnect" | Leaves the current channel |
| "Turn off riding mode" | Disables riding mode |

When riding mode is active the screen stays on, the lock screen is bypassed, and the screen turns on when new audio arrives — so glancing at your phone while riding is enough to see who's talking.

### Speaker routing

The **Loudspeaker** toggle routes audio through the built-in speaker instead of the earpiece. On API 31+ this uses `AudioManager.setCommunicationDevice()`; on older devices it falls back to the deprecated speakerphone API.

---

## Architecture

```
MainActivity
├── Volume key PTT (onKeyDown / onKeyUp)
├── Window flags for riding mode (FLAG_KEEP_SCREEN_ON, SHOW_WHEN_LOCKED)
└── WalkieTalkieService  ─────────────── Foreground Service
    │
    ├── HubConnectionManager          Star topology hub
    │   ├── BluetoothServerSocket     Accepts incoming RFCOMM connections
    │   ├── HalfDuplexLock            AtomicReference — one transmitter at a time
    │   └── per-client outbox         Channel<ByteArray> + dedicated writer coroutine
    │
    ├── ClientConnectionManager       Star topology client
    │   ├── BluetoothSocket           RFCOMM connection to hub
    │   └── manageClientChannel()     Auto-reconnect loop with exponential backoff
    │
    ├── AudioEngine                   Audio I/O
    │   ├── AudioRecord               Capture — VOICE_COMMUNICATION source, 16 kHz mono
    │   └── AudioTrack                Playback — USAGE_VOICE_COMMUNICATION
    │
    ├── OpusCodec                     Concentus pure-Java Opus compression
    │   ├── encode(pcmBytes)          Buffers to 640-sample frame → Opus packet (~80 bytes)
    │   └── decode(opusBytes)         Opus packet → raw PCM for AudioTrack
    │
    └── Riding-mode pipeline (lazy — created on first use)
        ├── WakeWordDetector          Porcupine on-device detection
        ├── SpeechToTextEngine        Android SpeechRecognizer (suspendCancellableCoroutine)
        └── VoiceCommandProcessor     Gemini 1.5 Flash function calling → VoiceCommand sealed class
```

### Audio data flow

```
Mic → AudioEngine.capturedAudio (SharedFlow<ByteArray>)
        ├── isTransmitting  → OpusCodec.encode() → Hub/ClientConnectionManager.sendAudio()
        └── ridingMode      → WakeWordDetector.feedAudio()
                                  └── on detection → stop capture → STT → Gemini
                                                    → executeCommand()
                                                    → restart capture

Inbound Opus packet → OpusCodec.decode() → AudioEngine.playFrame() → speaker
```

### Frame protocol

Binary frames over RFCOMM: `[TYPE : 1 byte][LENGTH : 2 bytes big-endian][PAYLOAD : N bytes]`

| Type | Byte | Payload |
|---|---|---|
| `Audio` | `0x01` | Opus-encoded PCM |
| `Busy` | `0x02` | _(empty)_ — client is starting to transmit |
| `Free` | `0x03` | _(empty)_ — client has stopped transmitting |
| `Hello` | `0x04` | Device name (UTF-8) |
| `Roster` | `0x05` | Newline-delimited member names (UTF-8) |
| `Blocked` | `0x06` | _(empty)_ — channel busy, transmission denied |

### Channel UUID

Channels are identified by a deterministic UUID derived from the channel name:

```kotlin
UUID.nameUUIDFromBytes("tw.channel.$name".toByteArray(Charsets.UTF_8))
```

Hub and clients independently compute the same UUID from the same name string, so no coordination is needed.

---

## Project structure

```
app/src/main/java/com/talkiewalkie/
  MainActivity.kt                       Entry point, permissions, volume key PTT, window flags
  audio/
    AudioEngine.kt                      AudioRecord + AudioTrack wrapper; SAMPLE_RATE = 16 000
    OpusCodec.kt                        Concentus Opus encode/decode; FRAME_BYTES = 1 280
  channel/
    ChannelManager.kt                   UUID derivation from channel name
    ClientConnectionManager.kt          RFCOMM client socket, inbound/outbound frame I/O
    HalfDuplexLock.kt                   AtomicReference<String?> — compareAndSet half-duplex
    HubConnectionManager.kt             RFCOMM server, per-client coroutines, roster broadcast
  model/
    WalkieState.kt                      ConnectionState sealed class, WalkieState data class, Role enum
  protocol/
    Frame.kt                            Sealed class for all 6 frame types
    FrameCodec.kt                       encode(Frame) / decode(InputStream) binary codec
  service/
    WalkieTalkieService.kt              Foreground service; orchestrates all subsystems
  ui/
    ChannelScreen.kt                    Channel name entry, Create / Join buttons
    MainScreen.kt                       Member roster, PTT button, riding mode + speaker toggles
    theme/Theme.kt                      Material 3 theme
  voice/
    SpeechToTextEngine.kt               Android SpeechRecognizer wrapped in suspendCancellableCoroutine
    VoiceCommand.kt                     Sealed class for parsed voice commands
    VoiceCommandProcessor.kt            Gemini 1.5 Flash with 6 function declarations
  wakeword/
    WakeWordDetector.kt                 Porcupine integration; feeds 512-sample frames

app/src/test/java/com/talkiewalkie/
  audio/OpusCodecTest.kt                Round-trip encode/decode, byte helper extensions
  channel/
    ChannelManagerTest.kt               UUID stability and case sensitivity
    HalfDuplexLockTest.kt               Contention test with CountDownLatch
  model/WalkieStateTest.kt              ConnectionState labels, isActive, copy semantics
  protocol/FrameCodecTest.kt            Round-trip all 6 frame types, truncation/error paths
```

---

## Key constants

| Constant | Value | Notes |
|---|---|---|
| `SAMPLE_RATE` | 16 000 Hz | Voice quality; matches Porcupine default |
| `FRAME_BYTES` | 1 280 bytes | 40 ms of mono 16-bit PCM; aligns to Opus frame size |
| Opus frame | 640 samples | 40 ms at 16 kHz |
| Opus bitrate | 16 000 bps | ~80 bytes/frame vs 1 280 raw — 16× compression |
| BT protocol | RFCOMM | Bluetooth Classic; no BLE |
| Wake word | PORCUPINE (built-in) | Swap `setKeyword()` for a custom `.ppn` file |
| Reconnect backoff | 2 s → 30 s cap | 10 attempts before giving up |
| Notification PTT | `ACTION_PTT_START` / `ACTION_PTT_STOP` | Delivered via `onStartCommand()` |
