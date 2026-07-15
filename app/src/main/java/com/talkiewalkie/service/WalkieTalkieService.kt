package com.talkiewalkie.service

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private const val CHANNEL_ID      = "walkie_talkie"
private const val NOTIFICATION_ID = 1

class WalkieTalkieService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var audioEngine: AudioEngine

    private var hubMgr:    HubConnectionManager?    = null
    private var clientMgr: ClientConnectionManager? = null

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
    }

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
        observeCapture()
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
                observeCapture()
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

    private fun observeCapture() {
        scope.launch {
            audioEngine.capturedAudio.collect { pcm ->
                if (!_state.value.isTransmitting) return@collect
                when (_state.value.role) {
                    Role.HUB    -> hubMgr?.broadcastAudio(pcm)
                    Role.CLIENT -> clientMgr?.sendAudio(pcm)
                    Role.NONE   -> {}
                }
            }
        }
    }

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

    private fun observeClientInbound() {
        scope.launch {
            clientMgr?.inbound?.collect { frame ->
                when (frame) {
                    is Frame.Audio   -> audioEngine.playFrame(frame.pcm)
                    is Frame.Roster  -> _state.update { it.copy(members = frame.members) }
                    is Frame.Blocked -> _state.update { it.copy(isBlocked = true, isTransmitting = false) }
                    else             -> {}
                }
            }
        }
    }

    private fun observeClientConnection() {
        scope.launch {
            clientMgr?.connected?.collect { connected ->
                if (!connected && _state.value.role == Role.CLIENT) {
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

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        hubMgr?.disconnect()
        clientMgr?.disconnect()
        audioEngine.release()
        scope.cancel()
        super.onDestroy()
    }

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
            s.isTransmitting         -> "Transmitting"
            s.currentTransmitter != null -> "Receiving from ${s.currentTransmitter}"
            s.connection.isActive    -> s.channelName?.let { "Channel: $it" } ?: s.connection.label
            else                     -> s.connection.label
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }
}
