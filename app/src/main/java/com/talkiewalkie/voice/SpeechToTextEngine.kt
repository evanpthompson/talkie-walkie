package com.talkiewalkie.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SpeechToTextEngine(private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    // Must be called from any coroutine — switches to Main internally.
    suspend fun listen(maxSilenceMs: Long = 2_000): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                cont.resumeWithException(SpeechRecognitionException(SpeechRecognitionException.UNAVAILABLE))
                return@suspendCancellableCoroutine
            }

            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    recognizer.destroy()
                    val text = results
                        .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (cont.isActive) cont.resume(text)
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (cont.isActive) {
                        cont.resumeWithException(SpeechRecognitionException(error))
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, maxSilenceMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }
            recognizer.startListening(intent)

            cont.invokeOnCancellation {
                recognizer.cancel()
                recognizer.destroy()
            }
        }
    }
}

class SpeechRecognitionException(val errorCode: Int) : Exception("SpeechRecognizer error $errorCode") {
    companion object {
        const val UNAVAILABLE = -1
    }
}
