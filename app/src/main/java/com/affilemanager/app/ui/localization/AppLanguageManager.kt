package com.affilemanager.app.ui.localization

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLanguageManager {
    const val ENGLISH = "en"
    const val LITHUANIAN = "lt"

    /**
     * Static, offline interface packs shipped with the APK. The list follows the broad language
     * set used by Android's established on-device translation ecosystem, while AF itself performs
     * no runtime translation and sends no interface text anywhere.
     */
    val SUPPORTED_LANGUAGE_TAGS = listOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "eo",
        "es", "et", "fa", "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr", "ht", "hu",
        "id", "is", "it", "ja", "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt",
        "nl", "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw", "ta", "te",
        "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    private val supportedLanguages = SUPPORTED_LANGUAGE_TAGS.toSet()

    private const val PREFS = "af_language"
    private const val INITIALIZED = "initialized"

    /** AF File Manager deliberately starts in English instead of inheriting the device locale. */
    fun ensureEnglishDefault(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getBoolean(INITIALIZED, false)) {
            // Android's resource matcher uses Filipino's canonical BCP-47 tag.
            // Migrate the formerly saved Tagalog alias without changing the choice.
            if (applicationLocales(context)[0]?.language == "tl") {
                applyLocales(context, LocaleListCompat.forLanguageTags("fil"))
            }
            return
        }
        preferences.edit().putBoolean(INITIALIZED, true).apply()
        applyLocales(context, LocaleListCompat.forLanguageTags(ENGLISH))
    }

    fun setLanguage(context: Context, languageTag: String) {
        val normalized = normalizeLanguageTag(languageTag)
        require(normalized in supportedLanguages) { "Unsupported interface language: $languageTag" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(INITIALIZED, true)
            .apply()
        applyLocales(context, LocaleListCompat.forLanguageTags(platformLanguageTag(normalized)))
        com.affilemanager.app.widgets.AfWidgetProvider.refresh(context)
    }

    internal fun applicationLocales(context: Context): LocaleListCompat =
        if (Build.VERSION.SDK_INT >= 33) LocaleManagerCompat.getApplicationLocales(context)
        else AppCompatDelegate.getApplicationLocales()

    private fun applyLocales(context: Context, locales: LocaleListCompat) {
        if (Build.VERSION.SDK_INT >= 33) {
            // A widget/service can run before any AppCompat Activity exists. Its static
            // delegate cannot obtain LocaleManager in that state, so use our own context.
            context.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
                android.os.LocaleList.forLanguageTags(locales.toLanguageTags())
        } else {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    fun isSupported(languageTag: String): Boolean = normalizeLanguageTag(languageTag) in supportedLanguages

    fun normalizeLanguageTag(languageTag: String): String = when (
        languageTag.substringBefore('-').lowercase(Locale.ROOT)
    ) {
        // Older Android releases can expose the legacy Java language aliases.
        "iw" -> "he"
        "in" -> "id"
        "fil" -> "tl"
        else -> languageTag.substringBefore('-').lowercase(Locale.ROOT)
    }

    fun platformLanguageTag(languageTag: String): String =
        normalizeLanguageTag(languageTag).let { if (it == "tl") "fil" else it }

    fun languageOptions(displayLocale: Locale): List<AppLanguageOption> = SUPPORTED_LANGUAGE_TAGS
        .map { tag ->
            val locale = Locale.forLanguageTag(tag)
            AppLanguageOption(
                tag = tag,
                nativeName = locale.getDisplayName(locale).titlecaseFirst(locale),
                displayName = locale.getDisplayName(displayLocale).titlecaseFirst(displayLocale),
                englishName = locale.getDisplayName(Locale.ENGLISH).titlecaseFirst(Locale.ENGLISH),
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

    private fun String.titlecaseFirst(locale: Locale): String = replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase(locale) else character.toString()
    }
}

data class AppLanguageOption(
    val tag: String,
    val nativeName: String,
    val displayName: String,
    val englishName: String,
)
