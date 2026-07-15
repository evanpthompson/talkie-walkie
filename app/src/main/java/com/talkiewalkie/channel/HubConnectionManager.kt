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
import java.util.concurrent.atomic.AtomicReference

sealed class HubEvent {
    data class ClientJoined(val name: String)           : HubEvent()
    data class ClientLeft(val name: String)             : HubEvent()
    data class AudioFrame(val from: String, val pcm: ByteArray) : HubEvent()
    data class TransmitterChanged(val who: String?)     : HubEvent()
}

private data class ConnectedClient(
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
    private val clients             = ConcurrentHashMap<String, ConnectedClient>()
    private val currentTransmitter  = AtomicReference<String?>(null)
    private var serverSocket: BluetoothServerSocket? = null

    private val _events = MutableSharedFlow<HubEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<HubEvent> = _events.asSharedFlow()

    val memberNames: List<String> get() = listOf(localName) + clients.keys().toList()

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
        val input  = socket.inputStream
        val output = socket.outputStream
        var clientName = socket.remoteDevice.address

        val first = FrameCodec.decode(input)
        if (first is Frame.Hello) clientName = first.deviceName

        val client = ConnectedClient(clientName, socket)
        clients[clientName] = client

        client.writerJob = scope.launch(Dispatchers.IO) {
            for (bytes in client.outbox) {
                try { output.write(bytes) } catch (_: Exception) { break }
            }
        }

        sendTo(clientName, Frame.Roster(memberNames))
        broadcastExcept(clientName, Frame.Roster(memberNames))
        _events.emit(HubEvent.ClientJoined(clientName))

        try {
            while (true) {
                val frame = FrameCodec.decode(input) ?: break
                when (frame) {
                    is Frame.Audio -> handleAudio(clientName, frame.pcm)
                    is Frame.Busy  -> handleBusy(clientName)
                    is Frame.Free  -> handleFree(clientName)
                    else           -> {}
                }
            }
        } finally {
            dropClient(clientName, socket)
        }
    }

    private suspend fun handleAudio(from: String, pcm: ByteArray) {
        if (currentTransmitter.get() == from) {
            _events.emit(HubEvent.AudioFrame(from, pcm))
            relayAudioExcept(from, pcm)
        }
    }

    private suspend fun handleBusy(from: String) {
        if (currentTransmitter.compareAndSet(null, from)) {
            _events.emit(HubEvent.TransmitterChanged(from))
        } else {
            sendTo(from, Frame.Blocked)
        }
    }

    private suspend fun handleFree(from: String) {
        if (currentTransmitter.compareAndSet(from, null)) {
            _events.emit(HubEvent.TransmitterChanged(null))
        }
    }

    fun acquireTransmitter(name: String): Boolean =
        currentTransmitter.compareAndSet(null, name).also { ok ->
            if (ok) scope.launch { _events.emit(HubEvent.TransmitterChanged(name)) }
        }

    fun releaseTransmitter(name: String) {
        if (currentTransmitter.compareAndSet(name, null)) {
            scope.launch { _events.emit(HubEvent.TransmitterChanged(null)) }
        }
    }

    fun broadcastAudio(pcm: ByteArray) {
        val encoded = FrameCodec.encode(Frame.Audio(pcm))
        clients.values.forEach { it.outbox.trySend(encoded) }
    }

    private fun relayAudioExcept(exclude: String, pcm: ByteArray) {
        val encoded = FrameCodec.encode(Frame.Audio(pcm))
        clients.forEach { (name, client) ->
            if (name != exclude) client.outbox.trySend(encoded)
        }
    }

    private fun sendTo(name: String, frame: Frame) {
        clients[name]?.outbox?.trySend(FrameCodec.encode(frame))
    }

    private fun broadcastExcept(exclude: String, frame: Frame) {
        val encoded = FrameCodec.encode(frame)
        clients.forEach { (name, client) ->
            if (name != exclude) client.outbox.trySend(encoded)
        }
    }

    private suspend fun dropClient(name: String, socket: BluetoothSocket) {
        clients.remove(name)?.let {
            it.writerJob?.cancel()
            runCatching { socket.close() }
        }
        if (currentTransmitter.compareAndSet(name, null)) {
            _events.emit(HubEvent.TransmitterChanged(null))
        }
        _events.emit(HubEvent.ClientLeft(name))
        broadcastExcept(name, Frame.Roster(memberNames))
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
