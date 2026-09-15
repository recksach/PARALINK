package com.paralink.app.core.language

import java.util.Locale

class OfflinePhraseTranslationEngine(
    private val corpus: PhraseStore,
    private val detector: LanguageDetector = LanguageDetector()
) : TranslationEngine {

    override suspend fun detectLanguage(text: String): LanguageDetectionResult =
        detector.detect(text)

    override suspend fun translate(
        text: String,
        source: Language,
        target: Language
    ): TranslationResult {
        if (text.isBlank()) {
            return TranslationResult(text, text, source, target, TranslationStatus.TRANSLATED)
        }

        var direct = translateFromDictionary(corpus.dictionary(source, target), text)
        if (direct.status == TranslationStatus.UNAVAILABLE && source != Language.EN && target != Language.EN) {
            val viaEn = translateFromDictionary(corpus.dictionary(source, Language.EN), text)
            if (viaEn.translated != null) {
                direct = translateFromDictionary(corpus.dictionary(Language.EN, target), viaEn.translated)
            }
        }

        return if (direct.status == TranslationStatus.UNAVAILABLE) {
            TranslationResult(text, null, source, target, TranslationStatus.UNAVAILABLE)
        } else {
            TranslationResult(text, direct.translated, source, target, direct.status)
        }
    }

    private fun translateFromDictionary(dict: Map<String, String>, text: String): TranslationResult {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return TranslationResult(text, text, null, Language.EN, TranslationStatus.TRANSLATED)

        val longestMatch = longestMatch(normalized, dict)
        if (longestMatch != null && longestMatch.value.length >= 2) {
            return TranslationResult(text, longestMatch.value, null, Language.EN, TranslationStatus.TRANSLATED)
        }

        val tokens = tokenizeWithPunctuation(normalized)
        val words = tokens.filter { it.isWord }
        val unknown = words.count { dict[it.text.lowercase(Locale.ROOT)] == null }
        if (words.size == 1 && unknown == 1) {
            return TranslationResult(text, null, null, Language.EN, TranslationStatus.UNAVAILABLE)
        }
        if (words.isEmpty()) {
            return TranslationResult(text, text, null, Language.EN, TranslationStatus.TRANSLATED)
        }

        val translated = tokens.joinToString("") { t ->
            if (t.isWord) dict[t.text.lowercase(Locale.ROOT)] ?: t.text else t.text
        }

        val status = if (unknown == 0) TranslationStatus.TRANSLATED else TranslationStatus.PARTIAL
        return TranslationResult(text, translated, null, Language.EN, status)
    }

    private class Token(val text: String, val isWord: Boolean)

    private fun tokenizeWithPunctuation(text: String): List<Token> {
        val result = ArrayList<Token>()
        val builder = StringBuilder()
        fun flush() {
            if (builder.isNotEmpty()) {
                result.add(Token(builder.toString(), true))
                builder.setLength(0)
            }
        }
        text.forEach { c ->
            if (c.isLetterOrDigit()) builder.append(c)
            else {
                flush()
                result.add(Token(c.toString(), false))
            }
        }
        flush()
        return result
    }

    private fun longestMatch(normalized: String, dict: Map<String, String>): Map.Entry<String, String>? {
        val candidates = dict.entries
            .filter { it.key.length <= normalized.length }
            .sortedByDescending { it.key.length }
        for (entry in candidates) {
            if (normalized.contains(entry.key)) return entry
        }
        return null
    }

    private fun normalize(text: String): String =
        text.trim().lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim('.', ',', '!', '?', ';', ':', '’', '\'', '"')
}