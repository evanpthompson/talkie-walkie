package com.talkiewalkie.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
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
        if (isCapturing) return     // idempotent — caller need not check first
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
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_OUT)
            .setEncoding(ENCODING)
            .build()
        player = AudioTrack(attrs, format, bufferSize, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE)
            .also { it.play() }
    }

    fun playFrame(pcm: ByteArray) {
        player?.write(pcm, 0, pcm.size)
    }

    fun stopPlayback() {
        player?.stop()
        player?.release()
        player = null
    }

    /**
     * Route playback audio to the built-in loudspeaker ([on] = true) or back to
     * the earpiece / default communication device ([on] = false).
     *
     * Uses the API-31+ setCommunicationDevice() path first; falls back to the
     * deprecated isSpeakerphoneOn setter on older devices.
     */
    fun setSpeakerOn(context: Context, on: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                val speaker = am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) am.setCommunicationDevice(speaker)
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
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

// Root-mean-square of a little-endian 16-bit PCM frame; 0.0 for empty arrays.
fun ByteArray.rms(): Double {
    if (size < 2) return 0.0
    var sum = 0.0
    val end = size and 1.inv()   // round down to even byte boundary
    for (i in 0 until end step 2) {
        val sample = ((this[i].toInt() and 0xFF) or (this[i + 1].toInt() shl 8)).toShort().toDouble()
        sum += sample * sample
    }
    return Math.sqrt(sum / (end / 2))
}
