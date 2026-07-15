package com.talkiewalkie.model

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Listening : ConnectionState()
    data class Connecting(val deviceName: String) : ConnectionState()
    data class Connected(val deviceName: String) : ConnectionState()

    val label: String get() = when (this) {
        is Disconnected -> "Not connected"
        is Listening    -> "Listening for peers…"
        is Connecting   -> "Connecting to $deviceName…"
        is Connected    -> "Connected — $deviceName"
    }

    val isConnected: Boolean get() = this is Connected
}

data class WalkieState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val isTransmitting: Boolean = false,
    val isReceiving: Boolean = false,
    val wakeWordEnabled: Boolean = true,
    val listeningForCommand: Boolean = false,
    val lastCommandText: String? = null,
)
