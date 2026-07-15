package com.talkiewalkie.audio

// RMS below this level is treated as silence and suppressed.
const val SQUELCH_THRESHOLD = 300.0

// Frames to keep open after RMS drops below threshold, preventing word-ending clipping.
// At 40 ms per frame, 6 frames = 240 ms of tail.
const val SQUELCH_HOLD_FRAMES = 6

/**
 * Gate that suppresses silent audio frames during PTT.
 *
 * Once RMS exceeds [threshold] the gate opens and stays open for [holdFrames]
 * additional frames after RMS falls back below the threshold. This prevents
 * word endings and consonants from being clipped.
 *
 * Call [reset] when PTT is released so stale hold state doesn't carry into
 * the next transmission.
 */
class SquelchGate(
    val threshold: Double = SQUELCH_THRESHOLD,
    val holdFrames: Int   = SQUELCH_HOLD_FRAMES,
) {
    private var holdCounter = 0

    /**
     * Returns true if the frame should be transmitted, false if it should be dropped.
     * Must be called once per captured frame while PTT is held.
     */
    fun shouldTransmit(pcmBytes: ByteArray): Boolean {
        return if (pcmBytes.rms() >= threshold) {
            holdCounter = holdFrames
            true
        } else if (holdCounter > 0) {
            holdCounter--
            true
        } else {
            false
        }
    }

    /** Clear the hold counter; call when PTT is released. */
    fun reset() { holdCounter = 0 }
}
