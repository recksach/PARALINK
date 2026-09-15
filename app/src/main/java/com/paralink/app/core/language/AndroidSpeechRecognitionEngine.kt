package com.paralink.app.core.language

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidSpeechRecognitionEngine(context: Context) : SpeechRecognitionEngine {

    private val contextRef = context.applicationContext
    private val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(contextRef)

    override val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(contextRef)

    override suspend fun transcribe(
        audio: ByteArray,
        language: Language?
    ): TranscriptionResult {
        if (audio.isEmpty()) {
            return TranscriptionResult("", language, available = true)
        }
        return TranscriptionResult("", language, available = false,
            error = "On-device transcription from an audio file is not available in this build.")
    }

    suspend fun listenLive(language: Language?): String {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language?.code ?: "en")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        return suspendCancellableCoroutine { cont ->
            val results = ArrayList<String>()
            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (cont.isActive) cont.resume(results.lastOrNull().orEmpty())
                }

                override fun onResults(results_: Bundle?) {
                    val text = results_?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (cont.isActive) cont.resume(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.let { results.add(it) }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }
            speechRecognizer.setRecognitionListener(listener)
            speechRecognizer.startListening(intent)
            cont.invokeOnCancellation { runCatching { speechRecognizer.cancel() } }
        }
    }
}