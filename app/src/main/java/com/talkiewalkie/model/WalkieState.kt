package com.talkiewalkie.model

enum class Role { NONE, HUB, CLIENT }

sealed class ConnectionState {
    data object Disconnected                    : ConnectionState()
    data object Hosting                         : ConnectionState()
    data object Searching                       : ConnectionState()
    data class  Connected(val deviceName: String) : ConnectionState()

    val label: String get() = when (this) {
        is Disconnected -> "Not connected"
        is Hosting      -> "Hosting channel…"
        is Searching    -> "Searching for hub…"
        is Connected    -> "Connected — $deviceName"
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
    val lastCommandText: String?     = null,
)
