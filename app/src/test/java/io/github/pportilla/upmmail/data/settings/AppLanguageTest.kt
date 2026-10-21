package io.github.pportilla.upmmail.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppLanguageTest {

    @Test
    fun `supported tags are kept and regions dropped`() {
        assertEquals("en", AppLanguage.normalize("en"))
        assertEquals("es", AppLanguage.normalize("es"))
        assertEquals("es", AppLanguage.normalize("es-ES"))
        assertEquals("es", AppLanguage.normalize("es_419"))
        assertEquals("en", AppLanguage.normalize(" EN-gb "))
    }

    @Test
    fun `anything else follows the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize("fr-FR"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.normalize("english"))
    }

    @Test
    fun `every choice is already normalised`() {
        AppLanguage.choices.forEach { assertEquals(it, AppLanguage.normalize(it)) }
        assertEquals(listOf("", "en", "es"), AppLanguage.choices)
    }

    @Test
    fun `system language has no locale`() {
        assertNull(AppLanguage.localeFor(AppLanguage.SYSTEM, listOf(Locale.forLanguageTag("es-ES"))))
        assertNull(AppLanguage.localeFor("fr", emptyList()))
    }

    @Test
    fun `a system locale in the chosen language keeps its region`() {
        val system = listOf(Locale.forLanguageTag("en-GB"), Locale.forLanguageTag("es-MX"), Locale.forLanguageTag("es-ES"))
        assertEquals(Locale.forLanguageTag("es-MX"), AppLanguage.localeFor("es", system))
        assertEquals(Locale.forLanguageTag("en-GB"), AppLanguage.localeFor("en", system))
    }

    @Test
    fun `otherwise the bare language is used`() {
        val system = listOf(Locale.forLanguageTag("fr-FR"))
        assertEquals(Locale.forLanguageTag("es"), AppLanguage.localeFor("es", system))
        assertEquals(Locale.forLanguageTag("en"), AppLanguage.localeFor("en", emptyList()))
    }
}
