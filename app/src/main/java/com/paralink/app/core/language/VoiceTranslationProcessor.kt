package com.paralink.app.core.language

data class VoiceProcessResult(
    val originalTranscript: String?,
    val translatedTranscript: String?,
    val originalAudioKept: Boolean
)

class VoiceTranslationProcessor(
    private val languageManager: LanguageManager,
    private val stt: SpeechRecognitionEngine? = null,
    private val tts: TextToSpeechEngine? = null
) {

    suspend fun process(audio: ByteArray): VoiceProcessResult {
        val transcript = stt?.takeIf { languageManager.translateVoice }?.transcribe(audio, null)
            ?.takeIf { it.available && it.text.isNotBlank() }?.text
            ?: return VoiceProcessResult(null, null, originalAudioKept = true)

        val incoming = languageManager.processIncoming(transcript)
        if (incoming.translated != null && incoming.detectedLanguage != null) {
            tts?.speak(incoming.translated, languageManager.targetLanguage)
        }
        return VoiceProcessResult(transcript, incoming.translated, originalAudioKept = true)
    }

    suspend fun processPtt(audio: ByteArray): VoiceProcessResult = process(audio)
}