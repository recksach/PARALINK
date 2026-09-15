package com.paralink.app.core.language

enum class TranslationStatus { TRANSLATED, PARTIAL, UNAVAILABLE }

data class TranslationResult(
    val original: String,
    val translated: String?,
    val sourceLanguage: Language?,
    val targetLanguage: Language,
    val status: TranslationStatus
)

interface TranslationEngine {
    suspend fun detectLanguage(text: String): LanguageDetectionResult

    suspend fun translate(
        text: String,
        source: Language,
        target: Language
    ): TranslationResult
}

interface SpeechRecognitionEngine {
    val available: Boolean
    suspend fun transcribe(
        audio: ByteArray,
        language: Language?
    ): TranscriptionResult
}

data class TranscriptionResult(
    val text: String,
    val language: Language?,
    val available: Boolean,
    val error: String? = null
)

interface TextToSpeechEngine {
    suspend fun speak(text: String, language: Language)
}