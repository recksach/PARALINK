package com.paralink.app.core.language

import java.io.InputStream
import java.util.Locale

class StatisticalMTEngine private constructor(
    private val lex: Map<String, List<Option>>,
    private val lm: LanguageModel,
    private val srcCode: String,
    private val tgtCode: String
) : TranslationEngine {

    private val detector = LanguageDetector()

    class Option internal constructor(val words: List<String>, val logP: Double)

    class LanguageModel internal constructor(
        private val bigrams: Map<String, Double>,
        private val unigrams: Map<String, Double>
    ) {
        private val unkLogP: Double = -8.0
        fun score(prev: String?, cur: String): Double {
            if (prev != null) {
                bigrams["$prev $cur"]?.let { return it }
            }
            return unigrams[cur] ?: unkLogP
        }
    }

    override suspend fun detectLanguage(text: String): LanguageDetectionResult =
        detector.detect(text)

    override suspend fun translate(
        text: String,
        source: Language,
        target: Language
    ): TranslationResult {
        if (source.code != srcCode || target.code != tgtCode) {
            return TranslationResult(text, null, source, target, TranslationStatus.UNAVAILABLE)
        }
        val words = tokenize(text)
        if (words.isEmpty()) {
            return TranslationResult(text, text, source, target, TranslationStatus.TRANSLATED)
        }
        val decoded = decode(words)
        if (decoded.out.isEmpty()) {
            return TranslationResult(text, null, source, target, TranslationStatus.UNAVAILABLE)
        }
        val normalized = (decoded.log / decoded.out.size).toDouble()
        val coverage = decoded.covered / words.size.toFloat()
        val status = when {
            coverage >= 1f && normalized >= TH_TRANSLATED -> TranslationStatus.TRANSLATED
            coverage >= 0.5f && normalized >= TH_PARTIAL -> TranslationStatus.PARTIAL
            else -> TranslationStatus.UNAVAILABLE
        }
        val translated = if (status == TranslationStatus.UNAVAILABLE) {
            null
        } else {
            decoded.out.joinToString(" ").replaceFirstChar { it.uppercase(Locale.ROOT) } + "."
        }
        return TranslationResult(text, translated, source, target, status)
    }

    private class BeamState(val out: List<String>, val log: Double)

    private class Decoded(val out: List<String>, val log: Double, val covered: Int)

    private fun decode(words: List<String>): Decoded {
        val n = words.size
        val dp = Array(n + 1) { mutableListOf<BeamState>() }
        dp[0].add(BeamState(emptyList(), 0.0))
        for (i in 0 until n) {
            prune(dp[i])
            val base = dp[i]
            if (base.isEmpty()) continue
            for (len in 1..MAX_PHRASE) {
                val end = i + len
                if (end > n) break
                val key = words.subList(i, end).joinToString(" ")
                val options = lex[key] ?: continue
                for (st in base) {
                    for (opt in options) {
                        val lmScore = lm.score(st.out.lastOrNull(), opt.words.first())
                        dp[end].add(BeamState(st.out + opt.words, st.log + opt.logP + lmScore))
                    }
                }
            }
            prune(dp[i])
        }
        val covered = (n downTo 0).first { dp[it].isNotEmpty() }
        val best = dp[covered].maxByOrNull { it.log }
            ?: return Decoded(emptyList(), 0.0, 0)
        return Decoded(best.out, best.log, covered)
    }

    private fun prune(states: MutableList<BeamState>) {
        if (states.size <= BEAM) return
        states.sortByDescending { it.log }
        states.subList(BEAM, states.size).clear()
    }

    private fun tokenize(text: String): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        val words = ArrayList<String>()
        val builder = StringBuilder()
        lower.forEach { c ->
            if (c.isLetter() || c == '\'') builder.append(c)
            else {
                if (builder.isNotEmpty()) {
                    words.add(builder.toString())
                    builder.setLength(0)
                }
            }
        }
        if (builder.isNotEmpty()) words.add(builder.toString())
        return words
    }

    companion object {
        private const val MAX_PHRASE = 4
        private const val BEAM = 64
        private const val TH_TRANSLATED = -1.7
        private const val TH_PARTIAL = -3.2

        fun load(input: InputStream): StatisticalMTEngine? {
            val lex = HashMap<String, MutableList<Option>>()
            val bigrams = HashMap<String, Double>()
            val unigrams = HashMap<String, Double>()
            var srcCode = "uk"
            var tgtCode = "de"
            var section = ""
            input.bufferedReader().forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachLine
                if (line.startsWith("#")) {
                    val header = line.removePrefix("#").trim()
                    if (header.startsWith("pair")) {
                        val parts = header.split(Regex("\\s+"))
                        if (parts.size >= 3) {
                            srcCode = parts[1]
                            tgtCode = parts[2]
                        }
                    } else if (header.startsWith("lex")) {
                        section = "lex"
                    } else if (header.startsWith("bigram")) {
                        section = "bigram"
                    } else if (header.startsWith("unigram")) {
                        section = "unigram"
                    }
                    return@forEachLine
                }
                val parts = line.split('\t')
                when (section) {
                    "lex" -> {
                        if (parts.size >= 3) {
                            val key = parts[0].trim()
                            val target = parts[1].trim()
                            val p = parts[2].toDoubleOrNull() ?: -1.0
                            lex.getOrPut(key) { mutableListOf() }
                                .add(Option(target.split(' '), p))
                        }
                    }
                    "bigram" -> {
                        if (parts.size >= 2) {
                            bigrams[parts[0].trim()] = parts[1].trim().toDoubleOrNull() ?: -2.0
                        }
                    }
                    "unigram" -> {
                        if (parts.size >= 2) {
                            unigrams[parts[0].trim()] = parts[1].trim().toDoubleOrNull() ?: -4.0
                        }
                    }
                }
            }
            if (lex.isEmpty()) return null
            return StatisticalMTEngine(lex, LanguageModel(bigrams, unigrams), srcCode, tgtCode)
        }
    }
}