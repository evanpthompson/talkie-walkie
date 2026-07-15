package com.talkiewalkie.service

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.media.AudioManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.talkiewalkie.BuildConfig
import com.talkiewalkie.MainActivity
import com.talkiewalkie.R
import com.talkiewalkie.audio.AudioEngine
import com.talkiewalkie.channel.ChannelManager
import com.talkiewalkie.channel.ClientConnectionManager
import com.talkiewalkie.channel.HubConnectionManager
import com.talkiewalkie.channel.HubEvent
import com.talkiewalkie.model.ConnectionState
import com.talkiewalkie.model.Role
import com.talkiewalkie.model.WalkieState
import com.talkiewalkie.protocol.Frame
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

    private var hubMgr:    HubConnectionManager?    = null
    private var clientMgr: ClientConnectionManager? = null

    // Riding-mode components — created on first use to avoid loading Porcupine
    // and STT when the user never enables riding mode.
    private var wakeWord:         WakeWordDetector?    = null
    private var stt:              SpeechToTextEngine?  = null
    private var commandProcessor: VoiceCommandProcessor? = null

    private var captureObserverJob:  Job? = null
    private var wakeWordObserverJob: Job? = null

    private val _state = MutableStateFlow(WalkieState())
    val state: StateFlow<WalkieState> = _state.asStateFlow()

    inner class LocalBinder : Binder() {
        fun service(): WalkieTalkieService = this@WalkieTalkieService
    }
    private val binder = LocalBinder()

    @SuppressLint("MissingPermission")
    private fun adapter() =
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @SuppressLint("MissingPermission")
    private fun localDeviceName(): String = adapter().name ?: android.os.Build.MODEL

    override fun onCreate() {
        super.onCreate()
        audioEngine = AudioEngine(scope)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Ready"))
        observeAudioMode()
    }

    private fun observeAudioMode() {
        scope.launch {
            state
                .map { it.connection.isActive }
                .distinctUntilChanged()
                .collect { isActive ->
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    am.mode = if (isActive) AudioManager.MODE_IN_COMMUNICATION
                              else          AudioManager.MODE_NORMAL
                }
        }
    }

    // ── channel lifecycle ─────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun createChannel(name: String) {
        val localName = localDeviceName()
        val uuid      = ChannelManager.channelUuid(name)
        hubMgr = HubConnectionManager(adapter(), uuid, localName, scope)
        _state.update {
            it.copy(
                channelName = name,
                role        = Role.HUB,
                connection  = ConnectionState.Hosting,
                members     = listOf(localName),
            )
        }
        hubMgr!!.start()
        audioEngine.startPlayback()
        audioEngine.startCapture()
        ensureCaptureObserver()
        observeHubEvents()
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    fun joinChannel(name: String) {
        val localName = localDeviceName()
        val uuid      = ChannelManager.channelUuid(name)
        val devices   = adapter().bondedDevices?.toList() ?: emptyList()
        clientMgr     = ClientConnectionManager(uuid, scope)
        _state.update {
            it.copy(
                channelName = name,
                role        = Role.CLIENT,
                connection  = ConnectionState.Searching,
            )
        }
        scope.launch {
            val hubName = clientMgr!!.connect(localName, devices)
            if (hubName != null) {
                _state.update { it.copy(connection = ConnectionState.Connected(hubName)) }
                audioEngine.startPlayback()
                audioEngine.startCapture()
                ensureCaptureObserver()
                observeClientInbound()
                observeClientConnection()
            } else {
                _state.update {
                    it.copy(
                        channelName = null,
                        role        = Role.NONE,
                        connection  = ConnectionState.Disconnected,
                    )
                }
            }
            updateNotification()
        }
    }

    // ── PTT ───────────────────────────────────────────────────────────────────

    fun startPtt() {
        val s = _state.value
        when (s.role) {
            Role.HUB -> {
                if (hubMgr?.acquireTransmitter(localDeviceName()) == true) {
                    _state.update { it.copy(isTransmitting = true) }
                }
            }
            Role.CLIENT -> {
                if (!s.isBlocked) {
                    clientMgr?.sendBusy()
                    _state.update { it.copy(isTransmitting = true) }
                }
            }
            Role.NONE -> {}
        }
        updateNotification()
    }

    fun stopPtt() {
        val s = _state.value
        when (s.role) {
            Role.HUB    -> hubMgr?.releaseTransmitter(localDeviceName())
            Role.CLIENT -> if (s.isTransmitting) clientMgr?.sendFree()
            Role.NONE   -> {}
        }
        _state.update { it.copy(isTransmitting = false, isBlocked = false) }
        updateNotification()
    }

    // ── riding mode ───────────────────────────────────────────────────────────

    fun setRidingMode(enabled: Boolean) {
        _state.update { it.copy(ridingMode = enabled) }
        if (enabled) {
            // Lazy-create voice pipeline components on first use.
            if (wakeWord == null) wakeWord = WakeWordDetector(this)
            if (stt      == null) stt      = SpeechToTextEngine(this)
            if (commandProcessor == null)
                commandProcessor = VoiceCommandProcessor(BuildConfig.GEMINI_API_KEY)

            // Capture must be running for Porcupine to receive audio.
            audioEngine.startCapture()       // idempotent
            ensureCaptureObserver()

            wakeWord!!.start(BuildConfig.PORCUPINE_ACCESS_KEY)

            if (wakeWordObserverJob?.isActive != true) {
                wakeWordObserverJob = scope.launch { observeWakeWordLoop() }
            }
        } else {
            wakeWord?.stop()
            wakeWordObserverJob?.cancel()
            wakeWordObserverJob = null
            // Leave capture running if we're in a channel; stop otherwise.
            if (!_state.value.connection.isActive) audioEngine.stopCapture()
        }
        updateNotification()
    }

    // ── speaker / native mic ──────────────────────────────────────────────────

    fun setSpeakerOn(on: Boolean) {
        _state.update { it.copy(speakerOn = on) }
        audioEngine.setSpeakerOn(this, on)
    }

    // ── audio capture routing ─────────────────────────────────────────────────

    private fun ensureCaptureObserver() {
        if (captureObserverJob?.isActive == true) return
        captureObserverJob = scope.launch {
            audioEngine.capturedAudio.collect { pcm ->
                val s = _state.value
                when {
                    s.isTransmitting -> when (s.role) {
                        Role.HUB    -> hubMgr?.broadcastAudio(pcm)
                        Role.CLIENT -> clientMgr?.sendAudio(pcm)
                        Role.NONE   -> {}
                    }
                    s.ridingMode && !s.listeningForCommand ->
                        wakeWord?.feedAudio(pcm)
                }
            }
        }
    }

    // ── hub event observer ────────────────────────────────────────────────────

    private fun observeHubEvents() {
        scope.launch {
            hubMgr?.events?.collect { event ->
                when (event) {
                    is HubEvent.ClientJoined       ->
                        _state.update { it.copy(members = hubMgr?.memberNames ?: it.members) }
                    is HubEvent.ClientLeft         ->
                        _state.update { it.copy(members = hubMgr?.memberNames ?: it.members) }
                    is HubEvent.AudioFrame         ->
                        audioEngine.playFrame(event.pcm)
                    is HubEvent.TransmitterChanged -> {
                        _state.update { it.copy(currentTransmitter = event.who) }
                        updateNotification()
                    }
                }
            }
        }
    }

    // ── client inbound observer ───────────────────────────────────────────────

    private fun observeClientInbound() {
        scope.launch {
            clientMgr?.inbound?.collect { frame ->
                when (frame) {
                    is Frame.Audio   -> audioEngine.playFrame(frame.pcm)
                    is Frame.Roster  -> _state.update { it.copy(members = frame.members) }
                    is Frame.Blocked -> _state.update {
                        it.copy(isBlocked = true, isTransmitting = false)
                    }
                    else             -> {}
                }
            }
        }
    }

    private fun observeClientConnection() {
        scope.launch {
            clientMgr?.connected?.collect { isConnected ->
                if (!isConnected && _state.value.role == Role.CLIENT) {
                    _state.update {
                        it.copy(
                            connection     = ConnectionState.Disconnected,
                            members        = emptyList(),
                            isTransmitting = false,
                            isBlocked      = false,
                        )
                    }
                    updateNotification()
                }
            }
        }
    }

    // ── wake-word → STT → Gemini pipeline ────────────────────────────────────

    private suspend fun observeWakeWordLoop() {
        wakeWord?.detections?.collect {
            _state.update { it.copy(listeningForCommand = true, lastCommandText = null) }
            updateNotification()

            // Free the mic so SpeechRecognizer gets exclusive access.
            audioEngine.stopCapture()

            val text = try {
                stt!!.listen()
            } catch (_: SpeechRecognitionException) {
                // STT unavailable or no speech — fall back to a 3-second PTT burst.
                audioEngine.startCapture()
                ensureCaptureObserver()
                startPtt()
                delay(3_000)
                stopPtt()
                return@collect
            } finally {
                audioEngine.startCapture()   // always restart capture
                ensureCaptureObserver()
                _state.update { it.copy(listeningForCommand = false) }
                updateNotification()
            }

            if (text.isNotBlank()) {
                _state.update { it.copy(lastCommandText = text) }
                val command = commandProcessor!!.process(text)
                executeCommand(command)
            }
        }
    }

    private suspend fun executeCommand(command: VoiceCommand) {
        when (command) {
            is VoiceCommand.CreateChannel    -> createChannel(command.channelName)
            is VoiceCommand.JoinChannel      -> joinChannel(command.channelName)
            is VoiceCommand.StartTransmitting -> {
                startPtt()
                delay(3_000)
                stopPtt()
            }
            is VoiceCommand.StopTransmitting -> stopPtt()
            is VoiceCommand.Disconnect       -> leaveChannel()
            is VoiceCommand.SetRidingMode    -> setRidingMode(command.enabled)
            is VoiceCommand.Unknown          -> {}
        }
    }

    private fun leaveChannel() {
        hubMgr?.disconnect()
        clientMgr?.disconnect()
        val preserved = _state.value.let { it.copy(ridingMode = it.ridingMode, speakerOn = it.speakerOn) }
        _state.value = WalkieState(ridingMode = preserved.ridingMode, speakerOn = preserved.speakerOn)
        updateNotification()
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        wakeWord?.stop()
        wakeWordObserverJob?.cancel()
        hubMgr?.disconnect()
        clientMgr?.disconnect()
        audioEngine.release()
        scope.cancel()
        super.onDestroy()
    }

    // ── notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
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
            s.listeningForCommand        -> "Listening for command…"
            s.ridingMode && !s.connection.isActive -> "Riding mode — say \"Porcupine\""
            s.isTransmitting             -> "Transmitting"
            s.currentTransmitter != null -> "Receiving from ${s.currentTransmitter}"
            s.connection.isActive        -> s.channelName?.let { "Channel: $it" } ?: s.connection.label
            else                         -> s.connection.label
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
