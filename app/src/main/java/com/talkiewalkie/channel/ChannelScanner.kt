package com.talkiewalkie.channel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class FoundChannel(val channelName: String, val deviceName: String)

// Client-side channel scanner.  Probes all bonded devices in parallel by
// connecting to DISCOVERY_UUID; devices running a hub respond with their
// channel name and immediately close the connection.
class ChannelScanner(private val scope: CoroutineScope) {

    private val _results  = MutableStateFlow<List<FoundChannel>>(emptyList())
    val results: StateFlow<List<FoundChannel>> = _results.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scanJob: Job? = null

    @SuppressLint("MissingPermission")
    fun scan(adapter: BluetoothAdapter) {
        if (_scanning.value) return
        scanJob?.cancel()
        scanJob = scope.launch {
            _results.value = emptyList()
            _scanning.value = true
            try {
                val devices = withContext(Dispatchers.IO) {
                    adapter.bondedDevices?.toList() ?: emptyList()
                }
                val found = coroutineScope {
                    devices.map { device -> async { probeDevice(device) } }.awaitAll()
                }
                _results.value = found.filterNotNull()
            } finally {
                _scanning.value = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun probeDevice(device: BluetoothDevice): FoundChannel? =
        withContext(Dispatchers.IO) {
            val socket = try {
                device.createInsecureRfcommSocketToServiceRecord(ChannelManager.DISCOVERY_UUID)
            } catch (_: Exception) { return@withContext null }
            try {
                socket.connect()
                val name = socket.inputStream.readBytes()
                    .toString(Charsets.UTF_8).trim()
                socket.close()
                if (name.isNotEmpty()) FoundChannel(name, device.name ?: device.address)
                else null
            } catch (_: Exception) {
                runCatching { socket.close() }
                null
            }
        }
}
