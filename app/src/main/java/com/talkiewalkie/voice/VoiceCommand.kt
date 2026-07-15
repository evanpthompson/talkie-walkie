package com.talkiewalkie.voice

sealed class VoiceCommand {
    data class ConnectToDevice(val deviceName: String) : VoiceCommand()
    data object StartTransmitting : VoiceCommand()
    data object StopTransmitting : VoiceCommand()
    data object Disconnect : VoiceCommand()
    data class SetWakeWord(val enabled: Boolean) : VoiceCommand()
    data class Unknown(val rawText: String) : VoiceCommand()
}
