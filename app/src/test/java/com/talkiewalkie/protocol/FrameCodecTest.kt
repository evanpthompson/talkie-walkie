package com.talkiewalkie.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class FrameCodecTest {

    private fun roundTrip(frame: Frame) =
        FrameCodec.decode(ByteArrayInputStream(FrameCodec.encode(frame)))

    // ── round-trip for every frame type ──────────────────────────────────────

    @Test fun audioRoundTrip() {
        val pcm = ByteArray(1280) { it.toByte() }
        val decoded = roundTrip(Frame.Audio(pcm)) as Frame.Audio
        assertArrayEquals(pcm, decoded.pcm)
    }

    @Test fun busyRoundTrip() = assertEquals(Frame.Busy, roundTrip(Frame.Busy))

    @Test fun freeRoundTrip() = assertEquals(Frame.Free, roundTrip(Frame.Free))

    @Test fun blockedRoundTrip() = assertEquals(Frame.Blocked, roundTrip(Frame.Blocked))

    @Test fun helloRoundTrip() {
        val decoded = roundTrip(Frame.Hello("Alice's Phone")) as Frame.Hello
        assertEquals("Alice's Phone", decoded.deviceName)
    }

    @Test fun helloUnicodeRoundTrip() {
        val name = "ライダー🏍"
        val decoded = roundTrip(Frame.Hello(name)) as Frame.Hello
        assertEquals(name, decoded.deviceName)
    }

    @Test fun rosterRoundTrip() {
        val members = listOf("Hub", "Alice", "Bob")
        val decoded = roundTrip(Frame.Roster(members)) as Frame.Roster
        assertEquals(members, decoded.members)
    }

    @Test fun emptyRosterRoundTrip() {
        val decoded = roundTrip(Frame.Roster(emptyList())) as Frame.Roster
        assertEquals(emptyList<String>(), decoded.members)
    }

    // ── header byte layout ────────────────────────────────────────────────────

    @Test fun headerLengthEncoding300Bytes() {
        // 300 decimal = 0x012C → high byte 0x01, low byte 0x2C
        val encoded = FrameCodec.encode(Frame.Audio(ByteArray(300)))
        assertEquals(0x01.toByte(), encoded[1])
        assertEquals(0x2C.toByte(), encoded[2])
    }

    @Test fun zeroLengthPayloadHasCorrectHeader() {
        val encoded = FrameCodec.encode(Frame.Busy)
        assertEquals(3, encoded.size)
        assertEquals(0x00.toByte(), encoded[1])
        assertEquals(0x00.toByte(), encoded[2])
    }

    @Test fun totalEncodedSizeIsThreePlusPayload() {
        val pcm = ByteArray(1280)
        val encoded = FrameCodec.encode(Frame.Audio(pcm))
        assertEquals(3 + 1280, encoded.size)
    }

    // ── error / edge cases ────────────────────────────────────────────────────

    @Test fun unknownTypeReturnsNull() {
        val raw = byteArrayOf(0xFF.toByte(), 0x00, 0x00)
        assertNull(FrameCodec.decode(ByteArrayInputStream(raw)))
    }

    @Test fun truncatedHeaderReturnsNull() {
        // Only 2 of 3 header bytes present
        val raw = byteArrayOf(0x01, 0x00)
        assertNull(FrameCodec.decode(ByteArrayInputStream(raw)))
    }

    @Test fun emptyStreamReturnsNull() {
        assertNull(FrameCodec.decode(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun headerDeclares16BytesButPayloadMissing() {
        val raw = byteArrayOf(0x01, 0x00, 0x10)   // TYPE_AUDIO, length=16, no payload
        assertNull(FrameCodec.decode(ByteArrayInputStream(raw)))
    }

    @Test fun headerDeclares16BytesButOnlyEightPresent() {
        val raw = ByteArray(3 + 8)
        raw[0] = 0x01; raw[1] = 0x00; raw[2] = 0x10  // length = 16
        assertNull(FrameCodec.decode(ByteArrayInputStream(raw)))
    }

    // ── split-read simulation ─────────────────────────────────────────────────

    @Test fun decodesCorrectlyWhenReadsAreSplitIntoSmallChunks() {
        val pcm = ByteArray(1280) { (it % 127).toByte() }
        val encoded = FrameCodec.encode(Frame.Audio(pcm))

        val pipedIn  = PipedInputStream(4096)
        val pipedOut = PipedOutputStream(pipedIn)

        val writer = Thread {
            encoded.asList().chunked(7).forEach { chunk ->
                pipedOut.write(chunk.toByteArray())
                pipedOut.flush()
            }
            pipedOut.close()
        }
        writer.start()

        val decoded = FrameCodec.decode(pipedIn) as Frame.Audio
        writer.join()

        assertArrayEquals(pcm, decoded.pcm)
    }

    // ── multiple frames on same stream ────────────────────────────────────────

    @Test fun multipleFramesDecodedInSequence() {
        val buf = java.io.ByteArrayOutputStream()
        buf.write(FrameCodec.encode(Frame.Hello("Alice")))
        buf.write(FrameCodec.encode(Frame.Roster(listOf("Alice", "Bob"))))
        buf.write(FrameCodec.encode(Frame.Busy))

        val stream = ByteArrayInputStream(buf.toByteArray())
        val f1 = FrameCodec.decode(stream) as Frame.Hello
        val f2 = FrameCodec.decode(stream) as Frame.Roster
        val f3 = FrameCodec.decode(stream)

        assertEquals("Alice", f1.deviceName)
        assertEquals(listOf("Alice", "Bob"), f2.members)
        assertEquals(Frame.Busy, f3)
    }
}
