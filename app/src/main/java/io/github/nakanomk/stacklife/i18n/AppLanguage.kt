package io.github.nakanomk.stacklife.i18n

import java.util.Locale

/** Formatting and messages produced off the UI thread use the same supported locale as Android resources. */
internal object AppLanguage {
    @Volatile var locale: Locale = resolve(listOf(Locale.getDefault()))
        private set

    fun resolve(preferred: List<Locale>): Locale {
        for (candidate in preferred) when (candidate.language) {
            "en" -> return candidate
            "zh" -> return Locale.SIMPLIFIED_CHINESE
        }
        return Locale.ENGLISH
    }

    fun update(preferred: List<Locale>): Locale = resolve(preferred).also { locale = it }
}

internal fun uiText(en: String, zh: String): String = if (AppLanguage.locale.language == "zh") zh else en
internal inline fun uiText(en: () -> String, zh: () -> String): String = if (AppLanguage.locale.language == "zh") zh() else en()
