package com.talkiewalkie.channel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.talkiewalkie.protocol.Frame
import com.talkiewalkie.protocol.FrameCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class HubEvent {
    data class ClientJoined(val name: String)           : HubEvent()
    data class ClientLeft(val name: String)             : HubEvent()
    data class AudioFrame(val from: String, val pcm: ByteArray) : HubEvent()
    data class TransmitterChanged(val who: String?)     : HubEvent()
}

private data class ConnectedClient(
    // Stable identity — the remote device's Bluetooth MAC address. Used as the
    // clients map key and the half-duplex lock token so two clients sharing
    // the same user-visible device name can never collide with each other.
    val id: String,
    // Display-only; comes from the client's Hello frame and may not be unique.
    val name: String,
    val socket: BluetoothSocket,
    val outbox: Channel<ByteArray> = Channel(capacity = 256),
    var writerJob: Job? = null,
)

class HubConnectionManager(
    private val adapter: BluetoothAdapter,
    private val uuid: UUID,
    private val localName: String,
    private val scope: CoroutineScope,
) {
    // Keyed by client id (MAC address), not display name — see ConnectedClient.id.
    private val clients      = ConcurrentHashMap<String, ConnectedClient>()
    private val txLock       = HalfDuplexLock()
    private var serverSocket: BluetoothServerSocket? = null

    private val _events = MutableSharedFlow<HubEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<HubEvent> = _events.asSharedFlow()

    val memberNames: List<String> get() = listOf(localName) + clients.values.map { it.name }

    @SuppressLint("MissingPermission")
    fun start() {
        serverSocket = adapter.listenUsingRfcommWithServiceRecord("TalkieWalkie", uuid)
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    launch { handleClient(socket) }
                } catch (_: Exception) { break }
            }
        }
    }

    private suspend fun handleClient(socket: BluetoothSocket) {
        val input    = socket.inputStream
        val output   = socket.outputStream
        val clientId = socket.remoteDevice.address
        var displayName = clientId

        val first = FrameCodec.decode(input)
        if (first is Frame.Hello) displayName = first.deviceName

        val client = ConnectedClient(clientId, displayName, socket)
        clients[clientId] = client

        client.writerJob = scope.launch(Dispatchers.IO) {
            for (bytes in client.outbox) {
                try { output.write(bytes) } catch (_: Exception) { break }
            }
        }

        sendTo(clientId, Frame.Roster(memberNames))
        broadcastExcept(clientId, Frame.Roster(memberNames))
        _events.emit(HubEvent.ClientJoined(displayName))

        try {
            while (true) {
                val frame = FrameCodec.decode(input) ?: break
                when (frame) {
                    is Frame.Audio -> handleAudio(clientId, frame.pcm)
                    is Frame.Busy  -> handleBusy(clientId)
                    is Frame.Free  -> handleFree(clientId)
                    else           -> {}
                }
            }
        } finally {
            dropClient(clientId, socket)
        }
    }

    private suspend fun handleAudio(fromId: String, pcm: ByteArray) {
        if (txLock.current == fromId) {
            _events.emit(HubEvent.AudioFrame(clients[fromId]?.name ?: fromId, pcm))
            relayAudioExcept(fromId, pcm)
        }
    }

    private suspend fun handleBusy(fromId: String) {
        if (txLock.acquire(fromId)) {
            _events.emit(HubEvent.TransmitterChanged(clients[fromId]?.name ?: fromId))
        } else {
            sendTo(fromId, Frame.Blocked)
        }
    }

    private suspend fun handleFree(fromId: String) {
        if (txLock.release(fromId)) {
            _events.emit(HubEvent.TransmitterChanged(null))
        }
    }

    fun acquireTransmitter(name: String): Boolean =
        txLock.acquire(name).also { ok ->
            if (ok) scope.launch { _events.emit(HubEvent.TransmitterChanged(name)) }
        }

    fun releaseTransmitter(name: String) {
        if (txLock.release(name)) {
            scope.launch { _events.emit(HubEvent.TransmitterChanged(null)) }
        }
    }

    fun broadcastAudio(pcm: ByteArray) {
        val encoded = FrameCodec.encode(Frame.Audio(pcm))
        clients.values.forEach { it.outbox.trySend(encoded) }
    }

    private fun relayAudioExcept(excludeId: String, pcm: ByteArray) {
        val encoded = FrameCodec.encode(Frame.Audio(pcm))
        clients.forEach { (id, client) ->
            if (id != excludeId) client.outbox.trySend(encoded)
        }
    }

    private fun sendTo(id: String, frame: Frame) {
        clients[id]?.outbox?.trySend(FrameCodec.encode(frame))
    }

    private fun broadcastExcept(excludeId: String, frame: Frame) {
        val encoded = FrameCodec.encode(frame)
        clients.forEach { (id, client) ->
            if (id != excludeId) client.outbox.trySend(encoded)
        }
    }

    private suspend fun dropClient(id: String, socket: BluetoothSocket) {
        val removed = clients.remove(id)
        removed?.let {
            it.writerJob?.cancel()
            runCatching { socket.close() }
        }
        if (txLock.release(id)) {
            _events.emit(HubEvent.TransmitterChanged(null))
        }
        _events.emit(HubEvent.ClientLeft(removed?.name ?: id))
        broadcastExcept(id, Frame.Roster(memberNames))
    }

    fun disconnect() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.values.forEach {
            it.writerJob?.cancel()
            runCatching { it.socket.close() }
        }
        clients.clear()
    }
}
