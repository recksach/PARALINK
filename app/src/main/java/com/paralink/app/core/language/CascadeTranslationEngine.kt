package com.paralink.app.core.language

class CascadeTranslationEngine(
    private val delegates: List<TranslationEngine>
) : TranslationEngine {

    override suspend fun detectLanguage(text: String): LanguageDetectionResult {
        for (d in delegates) {
            val result = d.detectLanguage(text)
            if (!result.uncertain) return result
        }
        return delegates.firstOrNull()?.detectLanguage(text)
            ?: LanguageDetectionResult(null, 0.0, uncertain = true)
    }

    override suspend fun translate(
        text: String,
        source: Language,
        target: Language
    ): TranslationResult {
        for (d in delegates) {
            val result = d.translate(text, source, target)
            if (result.translated != null && result.status != TranslationStatus.UNAVAILABLE) {
                return result
            }
        }
        return TranslationResult(text, null, source, target, TranslationStatus.UNAVAILABLE)
    }
}