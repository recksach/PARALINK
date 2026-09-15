package com.paralink.app.core.language

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class AndroidTextToSpeechEngine(context: Context) : TextToSpeechEngine {

    private val ready = AtomicBoolean(false)
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.set(status == TextToSpeech.SUCCESS)
    }

    override suspend fun speak(text: String, language: Language) {
        if (!ready.get()) return
        suspendCancellableCoroutine { cont ->
            tts.language = Locale(language.code)
            val utteranceId = "paralink-${System.nanoTime()}"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            cont.invokeOnCancellation { runCatching { tts.stop() } }
        }
    }
}