package com.talkiewalkie.wakeword

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.content.Context
import com.talkiewalkie.audio.toShortArray
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class WakeWordDetector(private val context: Context) {

    private var porcupine: Porcupine? = null

    // Buffer to accumulate incoming PCM until we have a full Porcupine frame
    private var pcmBuffer = ShortArray(0)
    private var frameLength = 512 // default; updated after init

    private val _detections = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val detections: SharedFlow<Unit> = _detections.asSharedFlow()

    fun start(accessKey: String) {
        porcupine = Porcupine.Builder()
            .setAccessKey(accessKey)
            // Use PORCUPINE as a placeholder wake word.
            // Replace with a custom .ppn file for "Hey Walkie" or similar.
            .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
            .build(context)
        frameLength = porcupine!!.frameLength
        pcmBuffer = ShortArray(0)
    }

    // Call this with every PCM frame from AudioEngine while not transmitting.
    fun feedAudio(pcm: ByteArray) {
        val p = porcupine ?: return
        val incoming = pcm.toShortArray()
        pcmBuffer += incoming

        while (pcmBuffer.size >= frameLength) {
            val frame = pcmBuffer.copyOf(frameLength)
            pcmBuffer = pcmBuffer.copyOfRange(frameLength, pcmBuffer.size)
            try {
                if (p.process(frame) >= 0) {
                    _detections.tryEmit(Unit)
                }
            } catch (e: PorcupineException) {
                // frame processing error — safe to ignore
            }
        }
    }

    fun stop() {
        porcupine?.delete()
        porcupine = null
        pcmBuffer = ShortArray(0)
    }
}
