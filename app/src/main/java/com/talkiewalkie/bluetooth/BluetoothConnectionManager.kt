package com.talkiewalkie.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.talkiewalkie.model.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.util.UUID

private val WALKIE_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
private const val SERVICE_NAME = "TalkieWalkie"

// 40 ms of 16-bit mono PCM at 16 kHz
const val FRAME_BYTES = 1280

class BluetoothConnectionManager(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
) {
    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var readJob: Job? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _incomingAudio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingAudio: SharedFlow<ByteArray> = _incomingAudio.asSharedFlow()

    fun startAccepting() {
        scope.launch(Dispatchers.IO) {
            _state.value = ConnectionState.Listening
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, WALKIE_UUID)
                val socket = serverSocket!!.accept()
                serverSocket?.close()
                onConnected(socket)
            } catch (e: IOException) {
                if (_state.value is ConnectionState.Listening) {
                    _state.value = ConnectionState.Disconnected
                }
            }
        }
    }

    fun connectTo(device: BluetoothDevice) {
        scope.launch(Dispatchers.IO) {
            val name = device.name ?: device.address
            _state.value = ConnectionState.Connecting(name)
            try {
                val socket = device.createRfcommSocketToServiceRecord(WALKIE_UUID)
                adapter.cancelDiscovery()
                socket.connect()
                onConnected(socket)
            } catch (e: IOException) {
                _state.value = ConnectionState.Disconnected
            }
        }
    }

    private fun onConnected(socket: BluetoothSocket) {
        activeSocket = socket
        val name = socket.remoteDevice.name ?: socket.remoteDevice.address
        _state.value = ConnectionState.Connected(name)
        readJob = scope.launch(Dispatchers.IO) { readLoop(socket) }
    }

    private suspend fun readLoop(socket: BluetoothSocket) {
        val buffer = ByteArray(FRAME_BYTES * 2)
        val stream = socket.inputStream
        try {
            while (true) {
                val n = stream.read(buffer)
                if (n > 0) _incomingAudio.emit(buffer.copyOf(n))
            }
        } catch (e: IOException) {
            handleDisconnect()
        }
    }

    fun send(pcm: ByteArray) {
        scope.launch(Dispatchers.IO) {
            try {
                activeSocket?.outputStream?.write(pcm)
            } catch (e: IOException) {
                handleDisconnect()
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        activeSocket?.close()
        serverSocket?.close()
        activeSocket = null
        serverSocket = null
        _state.value = ConnectionState.Disconnected
    }

    private fun handleDisconnect() {
        activeSocket = null
        _state.value = ConnectionState.Disconnected
    }
}
