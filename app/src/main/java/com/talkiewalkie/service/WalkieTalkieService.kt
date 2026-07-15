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
import com.talkiewalkie.audio.OpusCodec
import com.talkiewalkie.audio.SquelchGate
import com.talkiewalkie.channel.ChannelManager
import com.talkiewalkie.channel.ClientConnectionManager
import com.talkiewalkie.channel.HubConnectionManager
import com.talkiewalkie.channel.HubEvent
import com.talkiewalkie.haptic.HapticEngine
import com.talkiewalkie.model.ConnectionState
import com.talkiewalkie.model.Role
import com.talkiewalkie.model.WalkieState
import com.talkiewalkie.prefs.ChannelPrefs
import com.talkiewalkie.protocol.Frame
import com.talkiewalkie.voice.SpeechRecognitionException
import com.talkiewalkie.voice.SpeechToTextEngine
import com.talkiewalkie.voice.VoiceCommand
import com.talkiewalkie.voice.VoiceCommandProcessor
import com.talkiewalkie.wakeword.WakeWordDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val CHANNEL_ID       = "walkie_talkie"
private const val NOTIFICATION_ID  = 1

private const val TX_TIMEOUT_SECS  = 30
private const val TX_WARNING_SECS  = 5

class WalkieTalkieService : Service() {

    companion object {
        const val ACTION_PTT_START = "com.talkiewalkie.ACTION_PTT_START"
        const val ACTION_PTT_STOP  = "com.talkiewalkie.ACTION_PTT_STOP"
        private const val REQ_PTT_START = 10
        private const val REQ_PTT_STOP  = 11
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var audioEngine: AudioEngine
    private lateinit var hapticEngine: HapticEngine
    private val opusCodec   = OpusCodec()
    private val squelchGate = SquelchGate()

    private var hubMgr:    HubConnectionManager?    = null
    private var clientMgr: ClientConnectionManager? = null

    // Riding-mode components — created on first use to avoid loading Porcupine
    // and STT when the user never enables riding mode.
    private var wakeWord:         WakeWordDetector?    = null
    private var stt:              SpeechToTextEngine?  = null
    private var commandProcessor: VoiceCommandProcessor? = null

    private var captureObserverJob:  Job? = null
    private var wakeWordObserverJob: Job? = null
    private var clientChannelJob:    Job? = null
    private var txTimeoutJob:        Job? = null

    private var lastRxMs = 0L

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
        audioEngine  = AudioEngine(scope)
        hapticEngine = HapticEngine(this)
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
        ChannelPrefs.save(this, name, Role.HUB)
        hubMgr!!.start()
        audioEngine.startPlayback()
        audioEngine.startCapture()
        ensureCaptureObserver()
        observeHubEvents()
        updateNotification()
    }

    fun joinChannel(name: String) {
        clientChannelJob?.cancel()
        _state.update {
            it.copy(
                channelName = name,
                role        = Role.CLIENT,
                connection  = ConnectionState.Searching,
            )
        }
        ChannelPrefs.save(this, name, Role.CLIENT)
        clientChannelJob = scope.launch { manageClientChannel(name) }
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private suspend fun manageClientChannel(channelName: String) {
        val maxAttempts  = 10
        var attempt      = 0
        var inboundJob: Job? = null
        try {
            while (attempt < maxAttempts) {
                if (attempt > 0) {
                    val delayMs = minOf(2_000L shl (attempt - 1), 30_000L)
                    _state.update { it.copy(connection = ConnectionState.Reconnecting(attempt)) }
                    updateNotification()
                    delay(delayMs)
                    if (_state.value.channelName != channelName) return
                }

                clientMgr?.disconnect()
                val mgr = ClientConnectionManager(
                    ChannelManager.channelUuid(channelName), scope
                )
                clientMgr = mgr

                val devices = adapter().bondedDevices?.toList() ?: emptyList()
                val hubName = mgr.connect(localDeviceName(), devices)

                if (hubName == null) {
                    attempt++
                    continue
                }

                // Successful connection.
                attempt = 0
                _state.update { it.copy(connection = ConnectionState.Connected(hubName)) }
                audioEngine.startPlayback()
                audioEngine.startCapture()
                ensureCaptureObserver()
                updateNotification()

                inboundJob?.cancel()
                inboundJob = scope.launch {
                    mgr.inbound.collect { frame ->
                        when (frame) {
                            is Frame.Audio   -> {
                                val now = System.currentTimeMillis()
                                if (now - lastRxMs > 500L) hapticEngine.rxStart()
                                lastRxMs = now
                                audioEngine.playFrame(opusCodec.decode(frame.pcm))
                            }
                            is Frame.Roster  -> _state.update { it.copy(members = frame.members) }
                            is Frame.Blocked -> {
                                hapticEngine.blocked()
                                _state.update { it.copy(isBlocked = true, isTransmitting = false) }
                            }
                            else             -> {}
                        }
                    }
                }

                // Suspend until BT drops.
                mgr.connected.first { !it }

                inboundJob.cancel()
                inboundJob = null

                if (_state.value.channelName != channelName) return

                attempt = 1
                _state.update { it.copy(
                    connection     = ConnectionState.Reconnecting(attempt),
                    members        = emptyList(),
                    isTransmitting = false,
                    isBlocked      = false,
                )}
                updateNotification()
            }
        } finally {
            inboundJob?.cancel()
        }

        // All reconnect attempts exhausted — give up.
        _state.update {
            it.copy(
                channelName    = null,
                role           = Role.NONE,
                connection     = ConnectionState.Disconnected,
                members        = emptyList(),
                isTransmitting = false,
                isBlocked      = false,
            )
        }
        updateNotification()
    }

    // ── PTT ───────────────────────────────────────────────────────────────────

    fun startPtt() {
        val s = _state.value
        val acquired = when (s.role) {
            Role.HUB -> hubMgr?.acquireTransmitter(localDeviceName()) == true
            Role.CLIENT -> !s.isBlocked
            Role.NONE -> false
        }
        if (!acquired) { updateNotification(); return }

        if (s.role == Role.CLIENT) clientMgr?.sendBusy()
        _state.update { it.copy(isTransmitting = true) }
        hapticEngine.pttOpen()
        updateNotification()

        txTimeoutJob?.cancel()
        txTimeoutJob = scope.launch {
            delay((TX_TIMEOUT_SECS - TX_WARNING_SECS) * 1_000L)
            var remaining = TX_WARNING_SECS
            while (remaining > 0 && _state.value.isTransmitting) {
                _state.update { it.copy(txSecondsLeft = remaining) }
                updateNotification()
                hapticEngine.txWarning()
                delay(1_000)
                remaining--
            }
            if (_state.value.isTransmitting) stopPtt()
        }
    }

    fun stopPtt() {
        val job = txTimeoutJob
        txTimeoutJob = null
        job?.cancel()

        val s = _state.value
        if (s.isTransmitting) hapticEngine.pttClose()
        when (s.role) {
            Role.HUB    -> hubMgr?.releaseTransmitter(localDeviceName())
            Role.CLIENT -> if (s.isTransmitting) clientMgr?.sendFree()
            Role.NONE   -> {}
        }
        _state.update { it.copy(isTransmitting = false, isBlocked = false, txSecondsLeft = null) }
        opusCodec.resetEncoder()
        squelchGate.reset()
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
                    s.isTransmitting -> {
                        if (squelchGate.shouldTransmit(pcm)) {
                            val packets = opusCodec.encode(pcm)
                            for (packet in packets) {
                                when (s.role) {
                                    Role.HUB    -> hubMgr?.broadcastAudio(packet)
                                    Role.CLIENT -> clientMgr?.sendAudio(packet)
                                    Role.NONE   -> {}
                                }
                            }
                        }
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
                        audioEngine.playFrame(opusCodec.decode(event.pcm))
                    is HubEvent.TransmitterChanged -> {
                        val local = localDeviceName()
                        if (event.who != null && event.who != local) hapticEngine.rxStart()
                        _state.update { it.copy(currentTransmitter = event.who) }
                        updateNotification()
                    }
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
        clientChannelJob?.cancel()
        clientChannelJob = null
        hubMgr?.disconnect()
        clientMgr?.disconnect()
        ChannelPrefs.clear(this)
        val s = _state.value
        _state.value = WalkieState(ridingMode = s.ridingMode, speakerOn = s.speakerOn)
        updateNotification()
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PTT_START -> startPtt()
            ACTION_PTT_STOP  -> stopPtt()
        }
        return START_NOT_STICKY
    }

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
        val tapPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapPi)
            .setOngoing(true)

        val s = _state.value
        if (s.connection.isActive) {
            if (s.isTransmitting) {
                val stopPi = PendingIntent.getService(
                    this, REQ_PTT_STOP,
                    Intent(this, WalkieTalkieService::class.java).apply { action = ACTION_PTT_STOP },
                    PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(0, "Stop", stopPi)
            } else {
                val startPi = PendingIntent.getService(
                    this, REQ_PTT_START,
                    Intent(this, WalkieTalkieService::class.java).apply { action = ACTION_PTT_START },
                    PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(0, "Transmit", startPi)
            }
        }

        return builder.build()
    }

    private fun updateNotification() {
        val s = _state.value
        val status = when {
            s.listeningForCommand        -> "Listening for command…"
            s.ridingMode && !s.connection.isActive -> "Riding mode — say \"Porcupine\""
            s.isTransmitting && s.txSecondsLeft != null -> "Transmitting — ${s.txSecondsLeft}s left"
            s.isTransmitting             -> "Transmitting"
            s.currentTransmitter != null -> "Receiving from ${s.currentTransmitter}"
            s.connection.isActive        -> s.channelName?.let { "Channel: $it" } ?: s.connection.label
            else                         -> s.connection.label
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
