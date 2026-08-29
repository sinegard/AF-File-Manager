package com.affilemanager.app.ui.localization

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiTranslationAssetsTest {
    private val placeholder = Regex("\\{(\\d+)}")
    private val androidFormat = Regex("%\\d+\\$[a-zA-Z]|%%")

    @Test
    fun everySupportedGeneratedLanguageHasACompleteOfflinePack() {
        assertEquals(59, AppLanguageManager.SUPPORTED_LANGUAGE_TAGS.size)
        assertEquals(
            AppLanguageManager.SUPPORTED_LANGUAGE_TAGS.size,
            AppLanguageManager.SUPPORTED_LANGUAGE_TAGS.distinct().size,
        )
        assertTrue("Arabic must be supported", "ar" in AppLanguageManager.SUPPORTED_LANGUAGE_TAGS)
        assertTrue("Every RTL family must be present", setOf("ar", "fa", "he", "ur").all(AppLanguageManager.SUPPORTED_LANGUAGE_TAGS::contains))

        val root = projectRoot()
        val assetDirectory = File(root, "app/src/main/assets/i18n")
        val index = JSONObject(File(assetDirectory, "index.json").readText())
        val sourceExact = index.getJSONArray("exact")
        val exactCount = sourceExact.length()
        val sourceTemplates = index.getJSONArray("templates")
        assertTrue("The translation catalog should cover the full interface", exactCount >= 1_400)
        assertTrue("Dynamic messages must use safe templates", sourceTemplates.length() >= 150)

        AppLanguageManager.SUPPORTED_LANGUAGE_TAGS
            .filterNot { it == AppLanguageManager.ENGLISH || it == AppLanguageManager.LITHUANIAN }
            .forEach { language ->
                val file = File(assetDirectory, "$language.json")
                assertTrue("Missing offline language pack: $language", file.isFile)
                val pack = JSONObject(file.readText())
                val exact = pack.getJSONArray("exact")
                val templates = pack.getJSONArray("templates")
                assertEquals("Exact entry count for $language", exactCount, exact.length())
                assertEquals("Template entry count for $language", sourceTemplates.length(), templates.length())
                var translatableEntries = 0
                var changedEntries = 0
                for (position in 0 until exact.length()) {
                    val sourceValue = sourceExact.getString(position)
                    val translatedValue = exact.getString(position)
                    assertTrue(
                        "Broken generated text for $language at entry $position",
                        '\uFFFD' !in translatedValue && "<AFPH" !in translatedValue && '\u0000' !in translatedValue,
                    )
                    if (Regex("[A-Za-z]").containsMatchIn(sourceValue)) {
                        translatableEntries += 1
                        if (sourceValue != translatedValue) changedEntries += 1
                    }
                    assertEquals(
                        "Android format mismatch for $language at entry $position",
                        androidFormat.findAll(sourceValue).map(MatchResult::value).sorted().toList(),
                        androidFormat.findAll(translatedValue).map(MatchResult::value).sorted().toList(),
                    )
                }
                assertTrue(
                    "Too much of the $language interface remained in English",
                    translatableEntries > 0 && changedEntries.toDouble() / translatableEntries >= 0.90,
                )
                for (position in 0 until templates.length()) {
                    assertEquals(
                        "Placeholder mismatch for $language at template $position",
                        placeholder.findAll(sourceTemplates.getString(position)).map { it.groupValues[1] }.sorted().toList(),
                        placeholder.findAll(templates.getString(position)).map { it.groupValues[1] }.sorted().toList(),
                    )
                }
            }
    }

    @Test
    fun localeConfigAndAndroidResourcesCoverTheSameLanguages() {
        val root = projectRoot()
        val localeConfig = File(root, "app/src/main/res/xml/locales_config.xml").readText()
        val configured = Regex("android:name=\"([^\"]+)\"")
            .findAll(localeConfig)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(AppLanguageManager.SUPPORTED_LANGUAGE_TAGS, configured)

        val defaultKeys = stringResourceKeys(File(root, "app/src/main/res/values/strings.xml"))
        AppLanguageManager.SUPPORTED_LANGUAGE_TAGS
            .filterNot { it == AppLanguageManager.ENGLISH || it == AppLanguageManager.LITHUANIAN }
            .forEach { language ->
                val strings = File(root, "app/src/main/res/values-$language/strings.xml")
                assertTrue("Missing Android strings for $language", strings.isFile)
                assertEquals("Android string keys for $language", defaultKeys, stringResourceKeys(strings))
            }
    }

    private fun projectRoot(): File = sequenceOf(File("."), File(".."))
        .map { it.canonicalFile }
        .firstOrNull { File(it, "app/src/main").isDirectory }
        ?: error("Project root not found")

    private fun stringResourceKeys(file: File): Set<String> = Regex("<string name=\"([^\"]+)\"")
        .findAll(file.readText())
        .map { it.groupValues[1] }
        .toSet()
}
