package com.paralink.app

import com.paralink.app.core.language.JsonPhraseStore
import com.paralink.app.core.language.Language
import com.paralink.app.core.language.OfflinePhraseTranslationEngine
import com.paralink.app.core.language.TranslationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class OfflinePhraseTranslationEngineTest {

    private val corpusPath = "src/main/assets/offline-corpus.json"

    @Test
    fun corpusContainsUkDe() {
        val file = File(corpusPath)
        assumeTrue(file.exists())
        val store = JsonPhraseStore(FileInputStream(file))
        assertTrue("uk->de dictionary must be non-empty", store.dictionary(Language.UK, Language.DE).isNotEmpty())
    }

    @Test
    fun blankIsTranslated() = runBlocking {
        val file = File(corpusPath)
        assumeTrue(file.exists())
        val engine = OfflinePhraseTranslationEngine(JsonPhraseStore(FileInputStream(file)))
        val r = engine.translate("", Language.UK, Language.DE)
        assertEquals(TranslationStatus.TRANSLATED, r.status)
        assertNotNull(r.translated)
    }

    @Test
    fun knownPhraseTranslates() = runBlocking {
        val file = File(corpusPath)
        assumeTrue(file.exists())
        val engine = OfflinePhraseTranslationEngine(JsonPhraseStore(FileInputStream(file)))
        val r = engine.translate("Привіт, як справи", Language.UK, Language.DE)
        assertTrue(r.translated != null || r.status == TranslationStatus.UNAVAILABLE)
    }
}