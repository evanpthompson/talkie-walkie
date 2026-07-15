package com.talkiewalkie.voice

sealed class VoiceCommand {
    data class  CreateChannel(val channelName: String)  : VoiceCommand()
    data class  JoinChannel(val channelName: String)    : VoiceCommand()
    data object StartTransmitting                       : VoiceCommand()
    data object StopTransmitting                        : VoiceCommand()
    data object Disconnect                              : VoiceCommand()
    data class  SetRidingMode(val enabled: Boolean)     : VoiceCommand()
    data class  Unknown(val rawText: String)            : VoiceCommand()
}
