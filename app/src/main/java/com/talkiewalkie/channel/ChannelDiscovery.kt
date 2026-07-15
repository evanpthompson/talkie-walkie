package com.talkiewalkie.channel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import kotlinx.coroutines.*
import java.io.IOException

// Hub-side discovery responder.  Keeps a BluetoothServerSocket open on
// DISCOVERY_UUID.  For every connecting client it writes the channel name
// and immediately closes that connection so the client can display the
// channel in its scan results without joining.
class ChannelDiscovery(
    private val adapter: BluetoothAdapter,
    private val scope: CoroutineScope,
) {
    private var serverSocket: BluetoothServerSocket? = null
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(channelName: String) {
        stop()
        val nameBytes = channelName.toByteArray(Charsets.UTF_8)
        job = scope.launch(Dispatchers.IO) {
            val srv = try {
                adapter.listenUsingInsecureRfcommWithServiceRecord(
                    "tw-discovery",
                    ChannelManager.DISCOVERY_UUID,
                )
            } catch (_: IOException) { return@launch }
            serverSocket = srv
            try {
                while (isActive) {
                    val conn = try { srv.accept() } catch (_: IOException) { break }
                    launch {
                        runCatching {
                            conn.outputStream.write(nameBytes)
                            conn.outputStream.flush()
                            conn.close()
                        }
                    }
                }
            } finally {
                runCatching { srv.close() }
                serverSocket = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }
}
