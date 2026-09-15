package com.paralink.app.core.language

import org.json.JSONObject

data class LanguageCapability(
    val code: String,
    val ui: Boolean,
    val textTranslation: Boolean,
    val speechRecognition: Boolean,
    val tts: Boolean
)

class LanguageManifest private constructor(
    private val capabilities: Map<String, LanguageCapability>
) {
    fun forLanguage(language: Language): LanguageCapability =
        capabilities[language.code] ?: LanguageCapability(language.code, true, false, false, false)

    fun supportsTextTranslation(language: Language): Boolean = forLanguage(language).textTranslation

    fun supportsUi(language: Language): Boolean = forLanguage(language).ui

    fun languages(): List<LanguageCapability> = capabilities.values.sortedBy { it.code }

    companion object {
        fun fromJson(raw: String): LanguageManifest {
            val root = JSONObject(raw)
            val array = root.optJSONArray("languages") ?: return LanguageManifest(emptyMap())
            val map = HashMap<String, LanguageCapability>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val cap = LanguageCapability(
                    code = o.optString("code"),
                    ui = o.optBoolean("ui", true),
                    textTranslation = o.optBoolean("textTranslation", false),
                    speechRecognition = o.optBoolean("speechRecognition", false),
                    tts = o.optBoolean("tts", false)
                )
                map[cap.code] = cap
            }
            return LanguageManifest(map)
        }
    }
}