package com.talkiewalkie.audio

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.concentus.OpusException

// Match AudioEngine's 40 ms capture frame: 640 samples at 16 kHz
private val OPUS_FRAME_SAMPLES = SAMPLE_RATE * 40 / 1000  // 640
private const val OPUS_BITRATE = 16_000
private const val MAX_PACKET   = 1275  // Opus spec max bytes for a single packet

class OpusCodec {

    private val encoder: OpusEncoder =
        OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP)
            .also { it.setBitrate(OPUS_BITRATE) }

    private val decoder: OpusDecoder = OpusDecoder(SAMPLE_RATE, 1)

    // Accumulate partial capture frames until a full 40 ms window is ready.
    private var pending = ShortArray(0)
    private val pktBuf  = ByteArray(MAX_PACKET)

    /**
     * Encode raw PCM bytes (little-endian 16-bit mono) into Opus packets.
     * Buffers partial frames internally; returns one packet per complete 40 ms window.
     * Typically returns exactly one packet per call when AudioEngine's FRAME_BYTES
     * matches OPUS_FRAME_SAMPLES * 2 (which it does: 640 * 2 = 1280 = FRAME_BYTES).
     */
    fun encode(pcmBytes: ByteArray): List<ByteArray> {
        pending += pcmBytes.toShortArray()
        if (pending.size < OPUS_FRAME_SAMPLES) return emptyList()

        val packets = mutableListOf<ByteArray>()
        while (pending.size >= OPUS_FRAME_SAMPLES) {
            val frame = pending.copyOf(OPUS_FRAME_SAMPLES)
            pending   = pending.copyOfRange(OPUS_FRAME_SAMPLES, pending.size)
            try {
                val n = encoder.encode(frame, 0, OPUS_FRAME_SAMPLES, pktBuf, 0, pktBuf.size)
                if (n > 0) packets.add(pktBuf.copyOf(n))
            } catch (_: OpusException) { /* drop on encode error */ }
        }
        return packets
    }

    /**
     * Decode one Opus packet into raw PCM bytes (little-endian 16-bit mono).
     * Returns an empty array on error; gaps are handled by Opus PLC at the decoder.
     */
    fun decode(packet: ByteArray): ByteArray {
        val out = ShortArray(OPUS_FRAME_SAMPLES)
        return try {
            val n = decoder.decode(packet, 0, packet.size, out, 0, OPUS_FRAME_SAMPLES, false)
            out.copyOf(n).toByteArray()
        } catch (_: OpusException) {
            ByteArray(0)
        }
    }

    /**
     * Clear the pre-encode buffer.
     * Call when a PTT session ends so stale samples don't bleed into the next one.
     */
    fun resetEncoder() { pending = ShortArray(0) }
}

// Little-endian 16-bit shorts → PCM bytes (inverse of ByteArray.toShortArray())
fun ShortArray.toByteArray(): ByteArray {
    val out = ByteArray(size * 2)
    for (i in indices) {
        out[i * 2]     = (this[i].toInt() and 0xFF).toByte()
        out[i * 2 + 1] = (this[i].toInt() ushr 8).toByte()
    }
    return out
}
