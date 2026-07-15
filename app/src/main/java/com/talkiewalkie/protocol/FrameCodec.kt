package com.talkiewalkie.protocol

import java.io.InputStream

private const val TYPE_AUDIO:   Byte = 0x01
private const val TYPE_BUSY:    Byte = 0x02
private const val TYPE_FREE:    Byte = 0x03
private const val TYPE_HELLO:   Byte = 0x04
private const val TYPE_ROSTER:  Byte = 0x05
private const val TYPE_BLOCKED: Byte = 0x06

object FrameCodec {

    fun encode(frame: Frame): ByteArray {
        val type: Byte = when (frame) {
            is Frame.Audio   -> TYPE_AUDIO
            is Frame.Busy    -> TYPE_BUSY
            is Frame.Free    -> TYPE_FREE
            is Frame.Hello   -> TYPE_HELLO
            is Frame.Roster  -> TYPE_ROSTER
            is Frame.Blocked -> TYPE_BLOCKED
        }
        val payload: ByteArray = when (frame) {
            is Frame.Audio   -> frame.pcm
            is Frame.Busy    -> ByteArray(0)
            is Frame.Free    -> ByteArray(0)
            is Frame.Hello   -> frame.deviceName.toByteArray(Charsets.UTF_8)
            is Frame.Roster  -> frame.members.joinToString("\n").toByteArray(Charsets.UTF_8)
            is Frame.Blocked -> ByteArray(0)
        }
        val len = payload.size
        return ByteArray(3 + len).also { out ->
            out[0] = type
            out[1] = (len shr 8).toByte()
            out[2] = len.toByte()
            if (len > 0) payload.copyInto(out, 3)
        }
    }

    fun decode(input: InputStream): Frame? {
        val header = ByteArray(3)
        if (!readFully(input, header)) return null
        val type = header[0]
        val len  = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
        val payload = if (len > 0) {
            ByteArray(len).also { if (!readFully(input, it)) return null }
        } else {
            ByteArray(0)
        }
        return when (type) {
            TYPE_AUDIO   -> Frame.Audio(payload)
            TYPE_BUSY    -> Frame.Busy
            TYPE_FREE    -> Frame.Free
            TYPE_HELLO   -> Frame.Hello(payload.toString(Charsets.UTF_8))
            TYPE_ROSTER  -> Frame.Roster(
                if (payload.isEmpty()) emptyList()
                else payload.toString(Charsets.UTF_8).split("\n").filter { it.isNotEmpty() }
            )
            TYPE_BLOCKED -> Frame.Blocked
            else         -> null
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) return false
            offset += n
        }
        return true
    }
}
