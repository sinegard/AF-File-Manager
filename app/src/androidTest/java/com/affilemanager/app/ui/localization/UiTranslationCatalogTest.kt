package com.affilemanager.app.ui.localization

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.network.NetworkProtocol
import com.affilemanager.app.network.RemoteErrorPresenter
import com.affilemanager.app.network.RemoteOperation
import com.affilemanager.app.network.WebDavHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiTranslationCatalogTest {
    @Test
    fun webDavHintsUseTheSelectedLanguageWithoutTranslatingTheEndpointPath() {
        UiTranslationCatalog.initialize(ApplicationProvider.getApplicationContext<AFFileManagerApplication>())
        val auth = RemoteErrorPresenter.present(NetworkProtocol.WEBDAV, RemoteOperation.CONNECT, WebDavHttpException(401))
        val wrongPath = RemoteErrorPresenter.present(NetworkProtocol.WEBDAV, RemoteOperation.CONNECT, WebDavHttpException(405))
        for (language in listOf("pt", "es", "zh")) {
            val translatedAuth = UiTranslator.translate(auth.suggestion, language)
            assertNotEquals(UiTranslator.translate(auth.suggestion, "en"), translatedAuth)
            assertTrue(translatedAuth.contains("WebDAV"))
            assertTrue(UiTranslator.translate(wrongPath.suggestion, language).contains("/dav/"))
        }
        assertEquals("Folder unavailable", UiTranslator.translate("Aplankas nepasiekiamas", "en"))
        assertEquals("Aplankas nepasiekiamas", UiTranslator.translate("Aplankas nepasiekiamas", "lt"))
    }

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
