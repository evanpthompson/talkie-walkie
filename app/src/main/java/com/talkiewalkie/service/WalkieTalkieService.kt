package com.talkiewalkie.service

import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.talkiewalkie.BuildConfig
import com.talkiewalkie.MainActivity
import com.talkiewalkie.R
import com.talkiewalkie.audio.AudioEngine
import com.talkiewalkie.bluetooth.BluetoothConnectionManager
import com.talkiewalkie.model.WalkieState
import com.talkiewalkie.voice.SpeechRecognitionException
import com.talkiewalkie.voice.SpeechToTextEngine
import com.talkiewalkie.voice.VoiceCommand
import com.talkiewalkie.voice.VoiceCommandProcessor
import com.talkiewalkie.wakeword.WakeWordDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val CHANNEL_ID      = "walkie_talkie"
private const val NOTIFICATION_ID = 1

class WalkieTalkieService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var audioEngine: AudioEngine
    private lateinit var btManager: BluetoothConnectionManager
    private lateinit var wakeWord: WakeWordDetector
    private lateinit var stt: SpeechToTextEngine
    private lateinit var commandProcessor: VoiceCommandProcessor

    private val _state = MutableStateFlow(WalkieState())
    val state: StateFlow<WalkieState> = _state.asStateFlow()

    inner class LocalBinder : Binder() {
        fun service(): WalkieTalkieService = this@WalkieTalkieService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        audioEngine      = AudioEngine(scope)
        btManager        = BluetoothConnectionManager(adapter, scope)
        wakeWord         = WakeWordDetector(this)
        stt              = SpeechToTextEngine(this)
        commandProcessor = VoiceCommandProcessor(BuildConfig.GEMINI_API_KEY)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Ready"))

        observeConnectionState()
        observeIncomingAudio()
    }

    fun goLive() {
        audioEngine.startPlayback()
        audioEngine.startCapture()
        wakeWord.start(BuildConfig.PORCUPINE_ACCESS_KEY)
        btManager.startAccepting()
        observeCapture()
        observeWakeWord()
    }

    fun connectTo(deviceAddress: String) {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        btManager.connectTo(adapter.getRemoteDevice(deviceAddress))
    }

    fun startPtt() {
        _state.update { it.copy(isTransmitting = true) }
        updateNotification()
    }

    fun stopPtt() {
        _state.update { it.copy(isTransmitting = false) }
        updateNotification()
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        _state.update { it.copy(wakeWordEnabled = enabled) }
    }

    // Routes captured frames: transmit over BT, feed Porcupine, or drop (during STT).
    private fun observeCapture() {
        scope.launch {
            audioEngine.capturedAudio.collect { pcm ->
                val s = _state.value
                when {
                    s.isTransmitting       -> btManager.send(pcm)
                    s.wakeWordEnabled
                    && !s.listeningForCommand -> wakeWord.feedAudio(pcm)
                }
            }
        }
    }

    // Wake word → stop AudioRecord (free mic for STT) → STT → Gemini → execute command.
    private fun observeWakeWord() {
        scope.launch {
            wakeWord.detections.collect {
                _state.update { it.copy(listeningForCommand = true, lastCommandText = null) }
                updateNotification()

                // Release mic so SpeechRecognizer gets exclusive access.
                audioEngine.stopCapture()

                val text = try {
                    stt.listen()
                } catch (e: SpeechRecognitionException) {
                    // STT unavailable or no speech detected — fall back to a timed PTT burst.
                    audioEngine.startCapture()
                    _state.update { it.copy(listeningForCommand = false) }
                    startPtt()
                    delay(3_000)
                    stopPtt()
                    return@collect
                } finally {
                    // Always restart capture, even on error paths above.
                    if (!audioEngine.isCapturing) audioEngine.startCapture()
                    _state.update { it.copy(listeningForCommand = false) }
                    updateNotification()
                }

                if (text.isNotBlank()) {
                    _state.update { it.copy(lastCommandText = text) }
                    val command = commandProcessor.process(text)
                    executeCommand(command)
                }
            }
        }
    }

    private suspend fun executeCommand(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.ConnectToDevice  -> connectToByName(command.deviceName)
            is VoiceCommand.StartTransmitting -> {
                startPtt()
                delay(3_000)
                stopPtt()
            }
            is VoiceCommand.StopTransmitting -> stopPtt()
            is VoiceCommand.Disconnect       -> btManager.disconnect()
            is VoiceCommand.SetWakeWord      -> setWakeWordEnabled(command.enabled)
            is VoiceCommand.Unknown          -> { /* no action */ }
        }
    }

    @Suppress("MissingPermission")
    private fun connectToByName(name: String) {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val device  = adapter.bondedDevices
            ?.firstOrNull { it.name?.contains(name, ignoreCase = true) == true }
        if (device != null) {
            btManager.connectTo(device)
        } else {
            _state.update { it.copy(lastCommandText = "Device \"$name\" not found in paired devices") }
        }
    }

    private fun observeIncomingAudio() {
        scope.launch {
            btManager.incomingAudio.collect { pcm ->
                _state.update { it.copy(isReceiving = true) }
                audioEngine.playFrame(pcm)
                _state.update { it.copy(isReceiving = false) }
            }
        }
    }

    private fun observeConnectionState() {
        scope.launch {
            btManager.state.collect { conn ->
                _state.update { it.copy(connection = conn) }
                updateNotification()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        wakeWord.stop()
        audioEngine.release()
        btManager.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val s = _state.value
        val status = when {
            s.listeningForCommand -> "Listening for command…"
            s.isTransmitting      -> "Transmitting"
            else                  -> s.connection.label
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
