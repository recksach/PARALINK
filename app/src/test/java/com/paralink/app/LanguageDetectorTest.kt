package com.paralink.app

import com.paralink.app.core.language.Language
import com.paralink.app.core.language.LanguageDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageDetectorTest {

    private val detector = LanguageDetector()

    @Test
    fun detectsUkrainian() {
        val r = detector.detect("Я вчора зустрів людину, яка сказала, що приїде завтра.")
        assertEquals(Language.UK, r.language)
        assertTrue(!r.uncertain)
    }

    @Test
    fun detectsGerman() {
        val r = detector.detect("Ich habe gestern einen Menschen getroffen, der gesagt hat, dass er morgen kommt.")
        assertEquals(Language.DE, r.language)
        assertTrue(!r.uncertain)
    }

    @Test
    fun detectsEnglish() {
        val r = detector.detect("Yesterday I met a man who said he would come tomorrow.")
        assertEquals(Language.EN, r.language)
    }

    @Test
    fun shortInputIsUncertain() {
        val r = detector.detect("abc")
        assertEquals(null, r.language)
        assertTrue(r.uncertain)
    }
}