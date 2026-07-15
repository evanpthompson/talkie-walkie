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
import com.talkiewalkie.model.ConnectionState
import com.talkiewalkie.model.WalkieState
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

    private val _state = MutableStateFlow(WalkieState())
    val state: StateFlow<WalkieState> = _state.asStateFlow()

    inner class LocalBinder : Binder() {
        fun service(): WalkieTalkieService = this@WalkieTalkieService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        audioEngine = AudioEngine(scope)
        btManager   = BluetoothConnectionManager(adapter, scope)
        wakeWord    = WakeWordDetector(this)

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
        val device  = adapter.getRemoteDevice(deviceAddress)
        btManager.connectTo(device)
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

    // Routes captured audio frames: wake-word detection OR BT transmit, never both.
    private fun observeCapture() {
        scope.launch {
            audioEngine.capturedAudio.collect { pcm ->
                if (_state.value.isTransmitting) {
                    btManager.send(pcm)
                } else if (_state.value.wakeWordEnabled) {
                    wakeWord.feedAudio(pcm)
                }
            }
        }
    }

    private fun observeWakeWord() {
        scope.launch {
            wakeWord.detections.collect {
                startPtt()
                delay(3_000)
                stopPtt()
            }
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
        val status = _state.value.connection.label
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
