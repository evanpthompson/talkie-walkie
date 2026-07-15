package com.talkiewalkie.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

const val SAMPLE_RATE = 16_000
private const val CHANNEL_IN  = AudioFormat.CHANNEL_IN_MONO
private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
private const val ENCODING    = AudioFormat.ENCODING_PCM_16BIT

// 40 ms frame at 16 kHz mono 16-bit = 1280 bytes
val FRAME_BYTES = (SAMPLE_RATE * 2 * 40) / 1000

class AudioEngine(private val scope: CoroutineScope) {

    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureJob: Job? = null

    val isCapturing: Boolean get() = captureJob?.isActive == true

    private val _capturedAudio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val capturedAudio: SharedFlow<ByteArray> = _capturedAudio.asSharedFlow()

    fun startCapture() {
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING),
            FRAME_BYTES * 2
        )
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, bufferSize
        ).also { it.startRecording() }

        captureJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(FRAME_BYTES)
            while (isActive) {
                val n = recorder?.read(buf, 0, buf.size) ?: break
                if (n > 0) _capturedAudio.emit(buf.copyOf(n))
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        recorder?.stop()
        recorder?.release()
        recorder = null
    }

    fun startPlayback() {
        val bufferSize = maxOf(
            AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING),
            FRAME_BYTES * 4
        )
        player = AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            SAMPLE_RATE, CHANNEL_OUT, ENCODING,
            bufferSize, AudioTrack.MODE_STREAM
        ).also { it.play() }
    }

    fun playFrame(pcm: ByteArray) {
        player?.write(pcm, 0, pcm.size)
    }

    fun stopPlayback() {
        player?.stop()
        player?.release()
        player = null
    }

    fun release() {
        stopCapture()
        stopPlayback()
    }
}

// Little-endian PCM bytes → ShortArray for Porcupine
fun ByteArray.toShortArray(): ShortArray {
    val out = ShortArray(size / 2)
    for (i in out.indices) {
        out[i] = ((this[i * 2].toInt() and 0xFF) or (this[i * 2 + 1].toInt() shl 8)).toShort()
    }
    return out
}
