package com.paralink.app

import com.paralink.app.core.language.InMemoryCacheStore
import com.paralink.app.core.language.Language
import com.paralink.app.core.language.TranslationCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationCacheTest {

    @Test
    fun storesAndFetches() = runBlocking {
        val store = InMemoryCacheStore(16)
        val cache = TranslationCache(store)
        assertNull(cache.cached(Language.UK, Language.DE, "привіт"))
        cache.cache(Language.UK, Language.DE, "привіт", "hallo")
        assertEquals("hallo", cache.cached(Language.UK, Language.DE, "привіт"))
        assertEquals(1, store.size())
    }

    @Test
    fun evictsBeyondLimit() {
        val store = InMemoryCacheStore(3)
        val cache = TranslationCache(store)
        repeat(5) { cache.cache(Language.UK, Language.DE, "key$it", "v$it") }
        assertEquals(3, store.size())
        assertNull(store.get(TranslationCache.key("uk", "de", "key0")))
    }

    @Test
    fun keysDifferAcrossLanguages() {
        val a = TranslationCache.key("uk", "de", "привіт")
        val b = TranslationCache.key("de", "uk", "привіт")
        require(a != b)
        assertEquals(a, TranslationCache.key("uk", "de", "привіт"))
    }
}