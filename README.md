# talkie-walkie

Android Bluetooth walkie-talkie — push-to-talk and voice-triggered, built in native Kotlin.

## Architecture

```
MainActivity
└── WalkieTalkieService (Foreground Service)
    ├── BluetoothConnectionManager  — RFCOMM server/client socket
    ├── AudioEngine                 — AudioRecord + AudioTrack (16 kHz mono PCM)
    └── WakeWordDetector            — Porcupine on-device wake word
```

Audio routing (inside `WalkieTalkieService`):

```
Mic → AudioEngine.capturedAudio
        ├── if transmitting  → BluetoothConnectionManager.send()
        └── if idle          → WakeWordDetector.feedAudio()
                                  └── on detection → startPtt() for 3 s

BluetoothConnectionManager.incomingAudio → AudioEngine.playFrame() → speaker
```

## Project structure

```
app/src/main/java/com/talkiewalkie/
  MainActivity.kt                     # entry point, permission flow, service bind
  audio/AudioEngine.kt                # AudioRecord + AudioTrack wrapper
  bluetooth/BluetoothConnectionManager.kt  # RFCOMM socket management
  model/WalkieState.kt                # state types (ConnectionState, WalkieState)
  service/WalkieTalkieService.kt      # foreground service, orchestrates all subsystems
  ui/MainScreen.kt                    # Compose UI
  ui/theme/Theme.kt                   # Material 3 theme
  wakeword/WakeWordDetector.kt        # Porcupine integration
```

## Setup

1. Get a free Porcupine access key at [console.picovoice.ai](https://console.picovoice.ai)
2. Paste it in `app/build.gradle.kts`:
   ```kotlin
   buildConfigField("String", "PORCUPINE_ACCESS_KEY", "\"your-key-here\"")
   ```
3. Open in Android Studio, sync Gradle, and run on two Android 8+ devices paired via Bluetooth.

## Key constants

| Constant | Value | Notes |
|---|---|---|
| Sample rate | 16 000 Hz | Voice quality, matches Porcupine default |
| Frame size | 1 280 bytes | 40 ms of mono 16-bit PCM |
| BT protocol | RFCOMM | Classic Bluetooth, designed for streaming |
| Wake word | PORCUPINE (built-in) | Swap for a custom `.ppn` keyword file |
| Wake PTT duration | 3 seconds | Configurable in `WalkieTalkieService` |

## Build phases

- **MVP (current)** — manual PTT, raw PCM over RFCOMM, two devices
- **Phase 2** — Opus codec, multi-device channel selector, squelch
- **Phase 3** — custom wake word, hardware PTT button (volume key), haptic feedback
