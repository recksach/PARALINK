package com.paralink.app.core.language

import java.security.MessageDigest

interface CacheStore {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun size(): Int
}

class InMemoryCacheStore(private val maxEntries: Int = 512) : CacheStore {
    private val store = LinkedHashMap<String, String>(0, 0.75f, true)

    override fun put(key: String, value: String) {
        synchronized(this) {
            store[key] = value
            while (store.size > maxEntries) store.remove(store.keys.first())
        }
    }

    override fun get(key: String): String? = synchronized(this) { store[key] }

    override fun size(): Int = synchronized(this) { store.size }
}

class TranslationCache(private val store: CacheStore) {

    companion object {
        fun key(source: String, target: String, original: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest("$source\u0000$target\u0000$original".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    suspend fun cached(
        source: Language,
        target: Language,
        original: String
    ): String? = store.get(key(source.code, target.code, original))

    fun cache(source: Language, target: Language, original: String, translated: String) {
        store.put(key(source.code, target.code, original), translated)
    }
}