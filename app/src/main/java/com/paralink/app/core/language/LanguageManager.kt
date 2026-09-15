package com.paralink.app.core.language

import android.content.Context

enum class DisplayMode { BOTH, ORIGINAL_ONLY, TRANSLATION_ONLY }

data class IncomingView(
    val original: String,
    val translated: String?,
    val detectedLanguage: Language?,
    val uncertain: Boolean,
    val unavailable: Boolean
)

class LanguageManager(context: Context, private val engine: TranslationEngine? = null) {

    private val prefs = context.getSharedPreferences("paralink_language", Context.MODE_PRIVATE)
    private val detector = LanguageDetector()
    private val cache = TranslationCache(PrefsCacheStore(prefs))

    var appLanguage: Language
        get() = Language.fromCode(prefs.getString(KEY_APP_LANG, null))
            ?: Language.fromAndroidLocale(java.util.Locale.getDefault())
            ?: Language.EN
        set(value) { prefs.edit().putString(KEY_APP_LANG, value.code).apply() }

    var communicationLanguage: Language
        get() = Language.fromCode(prefs.getString(KEY_COMM_LANG, null)) ?: appLanguage
        set(value) { prefs.edit().putString(KEY_COMM_LANG, value.code).apply() }

    var targetLanguage: Language
        get() = Language.fromCode(prefs.getString(KEY_TARGET_LANG, null)) ?: communicationLanguage
        set(value) { prefs.edit().putString(KEY_TARGET_LANG, value.code).apply() }

    var autoTranslation: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TRANSLATE, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_TRANSLATE, value).apply() }

    var autoDetectLanguage: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DETECT, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_DETECT, value).apply() }

    var showOriginal: Boolean
        get() = prefs.getBoolean(KEY_SHOW_ORIGINAL, true)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_ORIGINAL, value).apply() }

    var translateVoice: Boolean
        get() = prefs.getBoolean(KEY_VOICE, true)
        set(value) { prefs.edit().putBoolean(KEY_VOICE, value).apply() }

    var translatePtt: Boolean
        get() = prefs.getBoolean(KEY_PTT, true)
        set(value) { prefs.edit().putBoolean(KEY_PTT, value).apply() }

    var callCaptions: Boolean
        get() = prefs.getBoolean(KEY_CAPTIONS, false)
        set(value) { prefs.edit().putBoolean(KEY_CAPTIONS, value).apply() }

    var displayMode: DisplayMode
        get() = when (prefs.getString(KEY_DISPLAY_MODE, DisplayMode.BOTH.name)) {
            DisplayMode.ORIGINAL_ONLY.name -> DisplayMode.ORIGINAL_ONLY
            DisplayMode.TRANSLATION_ONLY.name -> DisplayMode.TRANSLATION_ONLY
            else -> DisplayMode.BOTH
        }
        set(value) { prefs.edit().putString(KEY_DISPLAY_MODE, value.name).apply() }

    var firstRunDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, false)
        set(value) { prefs.edit().putBoolean(KEY_FIRST_RUN, value).apply() }

    val detectedSystemLanguage: Language
        get() = Language.fromAndroidLocale(java.util.Locale.getDefault()) ?: Language.EN

    suspend fun processIncoming(text: String): IncomingView {
        val target = targetLanguage

        var detected: Language? = null
        var uncertain = false
        if (autoDetectLanguage) {
            val detection = detector.detect(text)
            detected = detection.language
            uncertain = detection.uncertain
        }

        if (!autoTranslation) {
            return IncomingView(text, null, detected, uncertain, unavailable = false)
        }

        val engine = engine ?: return IncomingView(text, null, detected, uncertain, unavailable = true)
        val source = detected ?: communicationLanguage
        if (!target.supportsTranslationWith(source)) {
            return IncomingView(text, null, detected, uncertain, unavailable = true)
        }

        val cached = cache.cached(source, target, text)
        if (cached != null) {
            return IncomingView(text, cached, detected, uncertain, unavailable = false)
        }

        val result = engine.translate(text, source, target)
        if (result.translated != null) {
            cache.cache(source, target, text, result.translated)
            return IncomingView(text, result.translated, detected, uncertain, unavailable = false)
        }
        return IncomingView(text, null, detected, uncertain, unavailable = true)
    }

    private fun Language.supportsTranslationWith(other: Language): Boolean {
        if (this == other) return true
        val manifest = bundledManifest ?: return false
        return manifest.supportsTextTranslation(this) && manifest.supportsTextTranslation(other)
    }

    companion object {
        var bundledManifest: LanguageManifest? = null

        private const val KEY_APP_LANG = "app_language"
        private const val KEY_COMM_LANG = "comm_language"
        private const val KEY_TARGET_LANG = "target_language"
        private const val KEY_AUTO_TRANSLATE = "auto_translate"
        private const val KEY_AUTO_DETECT = "auto_detect"
        private const val KEY_SHOW_ORIGINAL = "show_original"
        private const val KEY_VOICE = "translate_voice"
        private const val KEY_PTT = "translate_ptt"
        private const val KEY_CAPTIONS = "call_captions"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_FIRST_RUN = "first_run_done"
    }
}

class PrefsCacheStore(
    private val prefs: android.content.SharedPreferences,
    private val maxEntries: Int = 300
) : CacheStore {
    override fun put(key: String, value: String) {
        val editor = prefs.edit()
        editor.putString(KEY_PREFIX + key, value)
        val keys = prefs.all.keys.filter { it.startsWith(KEY_PREFIX) }.sortedBy { it }
        if (keys.size > maxEntries) {
            keys.take(keys.size - maxEntries).forEach { editor.remove(it) }
        }
        editor.apply()
    }

    override fun get(key: String): String? = prefs.getString(KEY_PREFIX + key, null)

    override fun size(): Int = prefs.all.keys.count { it.startsWith(KEY_PREFIX) }

    companion object {
        private const val KEY_PREFIX = "t:"
    }
}