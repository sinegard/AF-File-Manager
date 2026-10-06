package com.affilemanager.app.ui.localization

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.data.OptionalFeature
import com.affilemanager.app.network.NetworkProtocol
import com.affilemanager.app.network.RemoteErrorPresenter
import com.affilemanager.app.network.RemoteOperation
import com.affilemanager.app.network.WebDavHttpException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiTranslationCatalogTest {
    @Test fun composedRuntimeFailuresAndTheirWrappersUseEverySelectedPack() {
        UiTranslationCatalog.initialize(ApplicationProvider.getApplicationContext<AFFileManagerApplication>())
        for (language in AppLanguageManager.SUPPORTED_LANGUAGE_TAGS) {
            val size = UiTranslator.translate("Dydis: 42 KB", language)
            val summary = UiTranslator.translate("Failai: 3 · aplankai: 0 · $size", language)
            assertTrue("$language analysis size label was lost", summary.contains(size))
            if (language != "lt") assertFalse("$language analysis retained Lithuanian", summary.contains("Dydis:"))
            val failure = UiTranslator.translate("Šiukšliadėžės katalogas neperskaitomas", language)
            val wrapper = UiTranslator.translate("Įspėjimas: Šiukšliadėžės katalogas neperskaitomas", language)
            assertTrue("$language warning body wasn't localized", wrapper.contains(failure))
            val path = "/Download/Šiukšliadėžė.txt"
            val missing = UiTranslator.translate("Klaida: Failas nepasiekiamas: $path", language)
            assertTrue("$language changed a user path", missing.contains(path))
            if (language != "lt") {
                assertFalse("$language retained a Lithuanian error fragment", failure.contains("neperskaitomas"))
            }
        }
    }

    @Test
    fun everyModelBackedFeatureLabelResolvesToItsShippedOfflineEntry() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        UiTranslationCatalog.initialize(application)
        val index = application.assets.open("i18n/index.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONArray("exact")
        }
        val positions = (0 until index.length()).associateBy { index.getString(it) }
        val protocols = setOf(OptionalFeature.WEB_SHARE, OptionalFeature.FTP_SHARE, OptionalFeature.WEBDAV_SHARE)
        for (language in AppLanguageManager.SUPPORTED_LANGUAGE_TAGS.filterNot { it in setOf("en", "lt") }) {
            val exact = application.assets.open("i18n/$language.json").bufferedReader().use {
                JSONObject(it.readText()).getJSONArray("exact")
            }
            OptionalFeature.entries.filterNot { it in protocols }.forEach { feature ->
                val canonical = UiTranslator.translate(feature.label, "en")
                val position = positions[canonical] ?: error("Feature not in catalog: ${feature.name}")
                assertEquals("$language/${feature.name} ignored its pack", exact.getString(position),
                    UiTranslator.translate(feature.label, language))
                assertNotEquals("$language/${feature.name} leaked Lithuanian", feature.label,
                    UiTranslator.translate(feature.label, language))
            }
        }
    }

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

    @Test
    fun anEnglishOriginatedRuntimeTemplateUsesTheSelectedOfflineLanguage() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        UiTranslationCatalog.initialize(application)

        for (language in listOf("es", "ar", "zh")) {
            val translated = UiTranslator.translate(
                "Source unavailable: /Download/report.txt (IOException)",
                language,
            )
            assertNotEquals("English runtime text leaked for $language", "Source unavailable: /Download/report.txt (IOException)", translated)
            assertTrue(translated.contains("/Download/report.txt"))
            assertTrue(translated.contains("IOException"))
        }
    }

    @Test
    fun filterDialogCopyExistsInEveryOfflineLanguagePack() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        UiTranslationCatalog.initialize(application)

        assertEquals("Filter", UiTranslator.translate("Filtras", "en"))
        assertEquals("All files", UiTranslator.translate("Visi failai", "en"))
        val index = application.assets.open("i18n/index.json").bufferedReader().use { reader ->
            JSONObject(reader.readText()).getJSONArray("exact")
        }
        val filterIndex = (0 until index.length()).first { index.getString(it) == "Filter" }
        val allFilesIndex = (0 until index.length()).first { index.getString(it) == "All files" }
        val runtimeFailureIndex = (0 until index.length()).first { index.getString(it) == "Working directory is unavailable" }
        AppLanguageManager.SUPPORTED_LANGUAGE_TAGS
            .filterNot { it in setOf(AppLanguageManager.ENGLISH, AppLanguageManager.LITHUANIAN) }
            .forEach { language ->
                val exact = application.assets.open("i18n/$language.json").bufferedReader().use { reader ->
                    JSONObject(reader.readText()).getJSONArray("exact")
                }
                val translatedFilter = UiTranslator.translate("Filtras", language)
                val translatedAllFiles = UiTranslator.translate("Visi failai", language)
                val translatedRuntimeFailure = UiTranslator.translate("Working directory is unavailable", language)
                assertEquals("Filter pack entry was not used for $language", exact.getString(filterIndex), translatedFilter)
                assertEquals("All files pack entry was not used for $language", exact.getString(allFilesIndex), translatedAllFiles)
                assertEquals(
                    "English-originated runtime failure pack entry was not used for $language",
                    exact.getString(runtimeFailureIndex),
                    translatedRuntimeFailure,
                )
                assertTrue("Blank Filter translation for $language", translatedFilter.isNotBlank())
                assertTrue("Blank All files translation for $language", translatedAllFiles.isNotBlank())
                assertTrue("Blank runtime failure translation for $language", translatedRuntimeFailure.isNotBlank())
                assertNotEquals("Leaked Lithuanian Filter title for $language", "Filtras", translatedFilter)
                assertNotEquals("Leaked Lithuanian All files label for $language", "Visi failai", translatedAllFiles)
            }
    }
}
