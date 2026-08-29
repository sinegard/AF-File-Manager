package com.affilemanager.app.ui.localization

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiTranslationCatalogTest {
    @Test
    fun staticSpanishAndArabicPacksLoadWithoutRuntimeTranslation() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        UiTranslationCatalog.initialize(application)

        val spanish = UiTranslator.translate("Failai", "es")
        val arabic = UiTranslator.translate("Failai", "ar")

        assertNotEquals("Files", spanish)
        assertNotEquals("Files", arabic)
        assertNotEquals(spanish, arabic)
    }

    @Test
    fun aDynamicTemplateTranslatesOnlyTheInterfaceAndPreservesThePath() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        UiTranslationCatalog.initialize(application)

        val translated = UiTranslator.translate("Išsaugota kaip /notes/report.txt", "es")

        assertNotEquals("Saved as /notes/report.txt", translated)
        assertTrue(translated.contains("/notes/report.txt"))
        assertEquals(1, "/notes/report.txt".toRegex(RegexOption.LITERAL).findAll(translated).count())
    }
}
