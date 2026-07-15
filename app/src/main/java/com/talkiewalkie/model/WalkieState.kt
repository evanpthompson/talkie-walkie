package com.talkiewalkie.model

enum class Role { NONE, HUB, CLIENT }

sealed class ConnectionState {
    data object Disconnected                        : ConnectionState()
    data object Hosting                             : ConnectionState()
    data object Searching                           : ConnectionState()
    data class  Connected(val deviceName: String)   : ConnectionState()
    data class  Reconnecting(val attempt: Int)       : ConnectionState()

    val label: String get() = when (this) {
        is Disconnected  -> "Not connected"
        is Hosting       -> "Hosting channel…"
        is Searching     -> "Searching for hub…"
        is Connected     -> "Connected — $deviceName"
        is Reconnecting  -> "Reconnecting… (attempt $attempt)"
    }

    val isActive: Boolean get() = this is Hosting || this is Connected
}

data class WalkieState(
    val channelName: String?         = null,
    val role: Role                   = Role.NONE,
    val connection: ConnectionState  = ConnectionState.Disconnected,
    val members: List<String>        = emptyList(),
    val isTransmitting: Boolean      = false,
    val isReceiving: Boolean         = false,
    val currentTransmitter: String?  = null,
    val isBlocked: Boolean           = false,
    // Riding mode: keeps microphone open and listens for the Porcupine wake
    // word even when no PTT is active — designed for hands-free use while moving.
    val ridingMode: Boolean          = false,
    // Routes playback to the built-in loudspeaker instead of the earpiece.
    val speakerOn: Boolean           = false,
    val listeningForCommand: Boolean = false,
    val lastCommandText: String?     = null,
)
