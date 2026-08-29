package com.affilemanager.app.ui.localization

import android.content.Context
import org.json.JSONObject
import java.util.LinkedHashMap

/**
 * Loads checked-in, static interface translations. Nothing is translated or transmitted while the
 * app is running. English text is the stable lookup key, while values such as file names and paths
 * are preserved by placeholder templates.
 */
object UiTranslationCatalog {
    private const val ASSET_DIRECTORY = "i18n"
    private const val INDEX_FILE = "$ASSET_DIRECTORY/index.json"
    private const val MAX_LOADED_LANGUAGES = 3
    private const val MAX_DYNAMIC_TRANSLATIONS = 512
    // Character classes are accepted by both the JVM and Android's ICU regex engine.
    // Android rejects the otherwise equivalent escaped-brace form (\{...}).
    private val placeholder = Regex("[{]([0-9]+)[}]")

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var index: TranslationIndex? = null

    private val catalogLock = Any()
    private val loadedCatalogs = object : LinkedHashMap<String, LanguageCatalog>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LanguageCatalog>?): Boolean =
            size > MAX_LOADED_LANGUAGES
    }

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun translate(canonicalEnglish: String, languageTag: String): String {
        if (canonicalEnglish.isBlank()) return canonicalEnglish
        val normalized = AppLanguageManager.normalizeLanguageTag(languageTag)
        if (normalized == AppLanguageManager.ENGLISH || normalized == AppLanguageManager.LITHUANIAN) {
            return canonicalEnglish
        }
        if (!AppLanguageManager.isSupported(normalized)) return canonicalEnglish

        val catalog = loadCatalog(normalized) ?: return canonicalEnglish
        catalog.exact[canonicalEnglish]?.let { translated ->
            if (translated.isNotBlank()) return translated
        }
        return catalog.translateDynamic(canonicalEnglish)
    }

    private fun loadCatalog(languageTag: String): LanguageCatalog? = synchronized(catalogLock) {
        loadedCatalogs[languageTag]?.let { return@synchronized it }
        val context = applicationContext ?: return@synchronized null
        runCatching {
            val loadedIndex = index ?: context.assets.open(INDEX_FILE).bufferedReader(Charsets.UTF_8).use { reader ->
                parseIndex(reader.readText()).also { index = it }
            }
            val translations = context.assets.open("$ASSET_DIRECTORY/$languageTag.json")
                .bufferedReader(Charsets.UTF_8)
                .use { reader -> parseTranslations(reader.readText()) }
            require(translations.exact.size == loadedIndex.exact.size) {
                "Exact translation count does not match for $languageTag"
            }
            require(translations.templates.size == loadedIndex.templates.size) {
                "Template translation count does not match for $languageTag"
            }

            LanguageCatalog(
                exact = loadedIndex.exact.indices.associate { position ->
                    loadedIndex.exact[position] to translations.exact[position]
                },
                templates = loadedIndex.templates.indices.mapNotNull { position ->
                    CompiledTemplate.create(
                        source = loadedIndex.templates[position],
                        target = translations.templates[position],
                    )
                },
            ).also { loadedCatalogs[languageTag] = it }
        }.getOrNull()
    }

    private fun parseIndex(json: String): TranslationIndex {
        val root = JSONObject(json)
        return TranslationIndex(
            exact = root.getJSONArray("exact").strings(),
            templates = root.getJSONArray("templates").strings(),
        )
    }

    private fun parseTranslations(json: String): TranslationValues {
        val root = JSONObject(json)
        return TranslationValues(
            exact = root.getJSONArray("exact").strings(),
            templates = root.getJSONArray("templates").strings(),
        )
    }

    private fun org.json.JSONArray.strings(): List<String> =
        List(length()) { position -> getString(position) }

    private data class TranslationIndex(
        val exact: List<String>,
        val templates: List<String>,
    )

    private data class TranslationValues(
        val exact: List<String>,
        val templates: List<String>,
    )

    private class LanguageCatalog(
        val exact: Map<String, String>,
        val templates: List<CompiledTemplate>,
    ) {
        private val dynamicLock = Any()
        private val dynamic = object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_DYNAMIC_TRANSLATIONS
        }

        fun translateDynamic(value: String): String = synchronized(dynamicLock) {
            dynamic[value]?.let { return@synchronized it }
            val translated = templates.firstNotNullOfOrNull { template -> template.translate(value) }
                ?.takeIf(String::isNotBlank)
                ?: value
            dynamic[value] = translated
            translated
        }
    }

    private data class CompiledTemplate(
        val matcher: Regex,
        val target: String,
        val captureOrder: List<Int>,
    ) {
        fun translate(value: String): String? {
            val match = matcher.matchEntire(value) ?: return null
            var translated = target
            captureOrder.forEachIndexed { captureIndex, placeholderIndex ->
                translated = translated.replace(
                    oldValue = "{$placeholderIndex}",
                    newValue = match.groupValues[captureIndex + 1],
                )
            }
            return translated
        }

        companion object {
            fun create(source: String, target: String): CompiledTemplate? {
                val tokens = placeholder.findAll(source).toList()
                if (tokens.isEmpty()) return null
                val captureOrder = tokens.map { it.groupValues[1].toInt() }
                if (captureOrder.any { "{$it}" !in target }) return null

                val pattern = buildString {
                    append('^')
                    var cursor = 0
                    tokens.forEach { token ->
                        append(Regex.escape(source.substring(cursor, token.range.first)))
                        append("(.+?)")
                        cursor = token.range.last + 1
                    }
                    append(Regex.escape(source.substring(cursor)))
                    append('$')
                }
                return CompiledTemplate(
                    matcher = Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL)),
                    target = target,
                    captureOrder = captureOrder,
                )
            }
        }
    }
}
