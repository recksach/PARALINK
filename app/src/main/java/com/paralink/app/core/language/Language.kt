package com.paralink.app.core.language

import java.util.Locale

enum class Language(
    val code: String,
    val name: String,
    val nativeName: String,
    val flag: String
) {
    EN("en", "English", "English", "\uD83C\uDDEC\uD83C\uDDE7"),
    UK("uk", "Ukrainian", "Українська", "\uD83C\uDDFA\uD83C\uDDE6"),
    RU("ru", "Russian", "Русский", "\uD83C\uDDF7\uD83C\uDDFA"),
    PL("pl", "Polish", "Polski", "\uD83C\uDDF5\uD83C\uDDF1"),
    DE("de", "German", "Deutsch", "\uD83C\uDDE9\uD83C\uDDEA"),
    FR("fr", "French", "Français", "\uD83C\uDDEB\uD83C\uDDF7"),
    ES("es", "Spanish", "Español", "\uD83C\uDDEC\uD83C\uDDEA"),
    IT("it", "Italian", "Italiano", "\uD83C\uDDEE\uD83C\uDDF9"),
    PT("pt", "Portuguese", "Português", "\uD83C\uDDF5\uD83C\uDDF9"),
    TR("tr", "Turkish", "Türkçe", "\uD83C\uDDF9\uD83C\uDDF7"),
    AR("ar", "Arabic", "العربية", "\uD83C\uDDF8\uD83C\uDDE6"),
    HI("hi", "Hindi", "हिन्दी", "\uD83C\uDDEE\uD83C\uDDF3"),
    ZH("zh", "Chinese", "中文", "\uD83C\uDDE8\uD83C\uDDF3"),
    JA("ja", "Japanese", "日本語", "\uD83C\uDDEF\uD83C\uDDF5"),
    KO("ko", "Korean", "한국어", "\uD83C\uDDF0\uD83C\uDDF7");

    val isRtl: Boolean get() = this == AR

    companion object {
        fun fromCode(code: String?): Language? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }

        fun fromAndroidLocale(locale: Locale): Language? {
            val lang = locale.language
            return when (lang) {
                "en", "uk", "ru", "pl", "de", "fr", "es", "it", "pt", "tr",
                "ar", "hi", "zh", "ja", "ko" -> fromCode(lang)
                "pt-BR", "ptb" -> PT
                "zh-Hans", "zh-CN", "zh-SG" -> ZH
                "zh-Hant", "zh-TW", "zh-HK" -> ZH
                else -> null
            }
        }

        val supported: List<Language> get() = entries.toList()
    }
}