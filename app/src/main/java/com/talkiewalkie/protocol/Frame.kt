package com.talkiewalkie.protocol

sealed class Frame {
    data class  Audio(val pcm: ByteArray)         : Frame()
    data object Busy                              : Frame()
    data object Free                              : Frame()
    data class  Hello(val deviceName: String)     : Frame()
    data class  Roster(val members: List<String>) : Frame()
    data object Blocked                           : Frame()
}
