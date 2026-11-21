package io.github.nakanomk.stacklife.i18n

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun followsFirstSupportedSystemLanguageWithoutGuessingFromRegion() {
        assertEquals(Locale.UK, AppLanguage.resolve(listOf(Locale.FRENCH, Locale.UK, Locale.CHINA)))
        assertEquals(Locale.SIMPLIFIED_CHINESE, AppLanguage.resolve(listOf(Locale.GERMAN, Locale.TAIWAN, Locale.US)))
    }

    @Test fun missingAndUnsupportedSystemLanguagesUseEnglish() {
        assertEquals(Locale.ENGLISH, AppLanguage.resolve(emptyList()))
        assertEquals(Locale.ENGLISH, AppLanguage.resolve(listOf(Locale.forLanguageTag("fr-CN"), Locale.ROOT)))
    }
}
