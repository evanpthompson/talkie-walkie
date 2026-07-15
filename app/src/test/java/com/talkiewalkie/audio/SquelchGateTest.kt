package com.talkiewalkie.audio

import org.junit.Assert.*
import org.junit.Test

class SquelchGateTest {

    private fun silence()  = ByteArray(FRAME_BYTES)                    // all-zero PCM
    private fun loud()     = ByteArray(FRAME_BYTES) { if (it % 2 == 0) 0x00 else 0x7F }  // ~16k RMS

    // ── basic threshold ────────────────────────────────────────────────────────

    @Test fun silenceIsSuppressed() {
        val gate = SquelchGate(threshold = SQUELCH_THRESHOLD)
        assertFalse(gate.shouldTransmit(silence()))
    }

    @Test fun loudAudioPasses() {
        val gate = SquelchGate(threshold = SQUELCH_THRESHOLD)
        assertTrue(gate.shouldTransmit(loud()))
    }

    // ── hold period ───────────────────────────────────────────────────────────

    @Test fun holdFramesPassAfterSpeechEnds() {
        val gate = SquelchGate(threshold = SQUELCH_THRESHOLD, holdFrames = 3)
        gate.shouldTransmit(loud())        // open the gate

        // Next 3 silent frames should still pass (hold countdown)
        assertTrue("hold frame 1", gate.shouldTransmit(silence()))
        assertTrue("hold frame 2", gate.shouldTransmit(silence()))
        assertTrue("hold frame 3", gate.shouldTransmit(silence()))

        // 4th silent frame should be suppressed
        assertFalse("frame after hold expired", gate.shouldTransmit(silence()))
    }

    @Test fun holdResetsOnSpeech() {
        val gate = SquelchGate(threshold = SQUELCH_THRESHOLD, holdFrames = 3)
        gate.shouldTransmit(loud())        // open
        gate.shouldTransmit(silence())     // hold frame 1 (counter = 2)
        gate.shouldTransmit(loud())        // speech again — counter resets to 3

        assertTrue("hold frame 1 after reset", gate.shouldTransmit(silence()))
        assertTrue("hold frame 2 after reset", gate.shouldTransmit(silence()))
        assertTrue("hold frame 3 after reset", gate.shouldTransmit(silence()))
        assertFalse("frame after second hold expired", gate.shouldTransmit(silence()))
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test fun resetClearsHoldCounter() {
        val gate = SquelchGate(threshold = SQUELCH_THRESHOLD, holdFrames = 5)
        gate.shouldTransmit(loud())        // open gate, holdCounter = 5
        gate.reset()                       // simulates PTT release

        // After reset, silence should be immediately suppressed
        assertFalse("silence after reset", gate.shouldTransmit(silence()))
    }

    @Test fun resetDoesNotAffectFutureDetection() {
        val gate = SquelchGate(threshold = SQUELCH_THRESHOLD, holdFrames = 3)
        gate.reset()
        // Should still detect loud audio normally after a reset
        assertTrue(gate.shouldTransmit(loud()))
    }

    // ── rms helper ────────────────────────────────────────────────────────────

    @Test fun rmsOfSilenceIsZero() {
        assertEquals(0.0, ByteArray(FRAME_BYTES).rms(), 0.001)
    }

    @Test fun rmsOfLoudSignalIsAboveThreshold() {
        val rms = loud().rms()
        assertTrue("RMS $rms should exceed threshold $SQUELCH_THRESHOLD", rms > SQUELCH_THRESHOLD)
    }

    @Test fun rmsOfEmptyArrayIsZero() {
        assertEquals(0.0, ByteArray(0).rms(), 0.0)
    }

    @Test fun rmsOfOddLengthArrayHandledSafely() {
        val oddBytes = ByteArray(3) { 0x7F.toByte() }
        val result = oddBytes.rms()
        assertTrue(result >= 0.0)   // must not throw; extra byte ignored
    }
}
