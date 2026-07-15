package com.talkiewalkie.channel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.talkiewalkie.protocol.Frame
import com.talkiewalkie.protocol.FrameCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

class ClientConnectionManager(
    private val uuid: UUID,
    private val scope: CoroutineScope,
) {
    private var socket: BluetoothSocket? = null
    private val outbox = Channel<ByteArray>(capacity = 256)

    private val _inbound = MutableSharedFlow<Frame>(extraBufferCapacity = 256)
    val inbound: SharedFlow<Frame> = _inbound.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // Returns the hub's device name on success, null on failure.
    @SuppressLint("MissingPermission")
    suspend fun connect(localName: String, devices: List<BluetoothDevice>): String? {
        for (device in devices) {
            val sock = runCatching { device.createRfcommSocketToServiceRecord(uuid) }
                .getOrNull() ?: continue
            try {
                withContext(Dispatchers.IO) { sock.connect() }
                socket = sock
                _connected.value = true
                startWriter(sock)
                startReader(sock)
                send(Frame.Hello(localName))
                return device.name ?: device.address
            } catch (_: Exception) {
                runCatching { sock.close() }
            }
        }
        return null
    }

    fun send(frame: Frame) = outbox.trySend(FrameCodec.encode(frame))

    fun sendBusy()               = send(Frame.Busy)
    fun sendFree()               = send(Frame.Free)
    fun sendAudio(pcm: ByteArray) = send(Frame.Audio(pcm))

    private fun startWriter(sock: BluetoothSocket) {
        scope.launch(Dispatchers.IO) {
            val output = sock.outputStream
            for (bytes in outbox) {
                try { output.write(bytes) } catch (_: Exception) { break }
            }
        }
    }

    private fun startReader(sock: BluetoothSocket) {
        scope.launch(Dispatchers.IO) {
            val input = sock.inputStream
            while (isActive) {
                val frame = FrameCodec.decode(input) ?: break
                _inbound.emit(frame)
            }
            _connected.value = false
        }
    }

    fun disconnect() {
        outbox.close()
        runCatching { socket?.close() }
        socket = null
        _connected.value = false
    }
}
