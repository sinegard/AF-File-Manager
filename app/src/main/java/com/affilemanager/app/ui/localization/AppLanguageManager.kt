package com.affilemanager.app.ui.localization

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguageManager {
    const val ENGLISH = "en"
    const val LITHUANIAN = "lt"

    private const val PREFS = "af_language"
    private const val INITIALIZED = "initialized"

    /** AF File Manager deliberately starts in English instead of inheriting the device locale. */
    fun ensureEnglishDefault(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getBoolean(INITIALIZED, false)) return
        preferences.edit().putBoolean(INITIALIZED, true).apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(ENGLISH))
    }

    fun setLanguage(context: Context, languageTag: String) {
        require(languageTag == ENGLISH || languageTag == LITHUANIAN)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(INITIALIZED, true)
            .apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
    }
}
