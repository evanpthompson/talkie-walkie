package com.talkiewalkie.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class OpusCodecTest {

    // ── ShortArray ↔ ByteArray helpers ───────────────────────────────────────

    @Test fun shortToByteRoundTrip() {
        val shorts = shortArrayOf(0, 1000, -1000, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes  = shorts.toByteArray()
        val back   = bytes.toShortArray()
        assertArrayEquals(shorts, back)
    }

    @Test fun byteToShortRoundTrip() {
        val bytes = ByteArray(8) { it.toByte() }
        val shorts = bytes.toShortArray()
        assertEquals(4, shorts.size)
        assertArrayEquals(bytes, shorts.toByteArray())
    }

    @Test fun zeroBytesYieldZeroShorts() {
        val result = ByteArray(640).toShortArray()
        assertEquals(320, result.size)
        assertTrue(result.all { it == 0.toShort() })
    }

    // ── OpusCodec encode/decode ────────────────────────────────────────────

    @Test fun silenceRoundTrip() {
        val codec = OpusCodec()
        val pcmIn = ByteArray(FRAME_BYTES)           // 1280 bytes of silence

        val packets = codec.encode(pcmIn)
        assertEquals("Expected exactly one Opus packet per 40 ms frame", 1, packets.size)
        assertTrue("Opus packet must be non-empty", packets[0].isNotEmpty())
        assertTrue("Opus packet must be smaller than raw PCM", packets[0].size < FRAME_BYTES)

        val decoded = codec.decode(packets[0])
        assertEquals("Decoded frame must be ${FRAME_BYTES} bytes", FRAME_BYTES, decoded.size)
    }

    @Test fun sineWaveRoundTrip() {
        val codec  = OpusCodec()
        val shorts = ShortArray(FRAME_BYTES / 2) { i ->
            (sin(2 * Math.PI * 440.0 * i / SAMPLE_RATE) * 16_000).toInt().toShort()
        }
        val pcmIn = shorts.toByteArray()

        val packets = codec.encode(pcmIn)
        assertEquals(1, packets.size)

        val decoded = codec.decode(packets[0]).toShortArray()
        assertEquals(shorts.size, decoded.size)

        // Rough sanity: decoded RMS should be within the same order of magnitude.
        val rmsIn  = rms(shorts)
        val rmsOut = rms(decoded)
        assertTrue("RMS went to zero after round-trip", rmsOut > rmsIn * 0.3)
        assertTrue("RMS inflated suspiciously after round-trip", rmsOut < rmsIn * 3.0)
    }

    @Test fun multiFrameAccumulation() {
        val codec  = OpusCodec()
        // Feed half a frame at a time; expect a packet only after the second chunk.
        val halfPcm = ByteArray(FRAME_BYTES / 2)

        val first  = codec.encode(halfPcm)
        assertEquals("Half-frame should not produce a packet yet", 0, first.size)

        val second = codec.encode(halfPcm)
        assertEquals("Two halves should produce exactly one packet", 1, second.size)
    }

    @Test fun resetEncoderClearsBuffer() {
        val codec = OpusCodec()
        // Feed half a frame…
        codec.encode(ByteArray(FRAME_BYTES / 2))
        // …reset, then feed another half: should still get nothing.
        codec.resetEncoder()
        val result = codec.encode(ByteArray(FRAME_BYTES / 2))
        assertEquals("Buffer should be empty after reset", 0, result.size)
    }

    @Test fun decodeEmptyPacketDoesNotCrash() {
        val codec  = OpusCodec()
        val result = codec.decode(ByteArray(0))
        // Should return empty or silence; must not throw.
        assertNotNull(result)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return Math.sqrt(sum / samples.size)
    }
}
