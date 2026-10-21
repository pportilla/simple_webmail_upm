package io.github.pportilla.upmmail.data.settings

import java.util.Locale

/**
 * Values of [AppSettings.language][io.github.pportilla.upmmail.model.AppSettings.language]: the
 * languages the app is translated into (as in res/xml/locales_config.xml) and the locale each
 * one stands for. Android-free; [io.github.pportilla.upmmail.UpmMailApp] applies the choice.
 */
object AppLanguage {
    /** Follow the system's language. */
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val SPANISH = "es"

    /** The choices offered in Settings, in display order. */
    val choices: List<String> = listOf(SYSTEM, ENGLISH, SPANISH)

    /** [language] as one of [choices]: a supported language tag ("es-ES" gives "es"), else [SYSTEM]. */
    fun normalize(language: String?): String {
        val primary = language.orEmpty().trim().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        return if (primary == ENGLISH || primary == SPANISH) primary else SYSTEM
    }

    /**
     * The locale for [language], or null for [SYSTEM]: the first of the user's [systemLocales] in
     * that language, which keeps their regional formats (es-ES, en-GB), else the bare language.
     */
    fun localeFor(language: String, systemLocales: List<Locale>): Locale? {
        val tag = normalize(language)
        if (tag == SYSTEM) return null
        return systemLocales.firstOrNull { it.language == tag } ?: Locale.forLanguageTag(tag)
    }
}
