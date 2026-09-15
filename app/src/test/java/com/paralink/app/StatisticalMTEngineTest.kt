package com.paralink.app

import com.paralink.app.core.language.Language
import com.paralink.app.core.language.StatisticalMTEngine
import com.paralink.app.core.language.TranslationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class StatisticalMTEngineTest {

    private val engine: StatisticalMTEngine? by lazy { load() }

    private fun load(): StatisticalMTEngine? {
        val file = File("src/main/assets/smt/uk-de.txt")
        if (!file.exists()) return null
        return StatisticalMTEngine.load(FileInputStream(file))
    }

    @Test
    fun translatesFreeFormSentence() = runBlocking {
        assumeTrue(engine != null)
        val r = engine!!.translate(
            "Я вчера встретил человека, который сказал, что приедет завтра.",
            Language.UK,
            Language.DE
        )
        assertNotNull(r.translated)
        assertTrue(r.status == TranslationStatus.TRANSLATED || r.status == TranslationStatus.PARTIAL)
        assertTrue(r.translated!!.contains("gestern"))
        assertTrue(r.translated!!.contains("morgen"))
    }

    @Test
    fun unknownTextIsUnavailable() = runBlocking {
        assumeTrue(engine != null)
        val r = engine!!.translate("zxqwbz qwerty lkjh", Language.UK, Language.DE)
        assertEquals(TranslationStatus.UNAVAILABLE, r.status)
        assertEquals(null, r.translated)
    }

    @Test
    fun unsupportedPairIsUnavailable() = runBlocking {
        assumeTrue(engine != null)
        val r = engine!!.translate("привіт", Language.UK, Language.PL)
        assertEquals(TranslationStatus.UNAVAILABLE, r.status)
    }

    @Test
    fun emptyTextIsTrivial() = runBlocking {
        assumeTrue(engine != null)
        val r = engine!!.translate("", Language.UK, Language.DE)
        assertEquals(TranslationStatus.TRANSLATED, r.status)
    }
}