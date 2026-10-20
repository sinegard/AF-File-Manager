package com.affilemanager.app.ui.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat

/** Background contexts can precede an Activity or a framework configuration update. */
fun Context.appLanguageContext(): Context {
    val chosen = AppLanguageManager.applicationLocales(this)
    if (chosen.isEmpty) {
        // AndroidX reads the persisted app locale even before an Activity starts in this process.
        return ContextCompat.getContextForLanguage(this)
    }
    val tags = chosen.toLanguageTags()
    if (resources.configuration.locales.toLanguageTags() == tags) return this
    val localized = Configuration(resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tags)) }
    return createConfigurationContext(localized)
}

fun Context.appString(@StringRes resource: Int, vararg arguments: Any): String {
    val context = appLanguageContext()
    return if (arguments.isEmpty()) context.getString(resource) else context.getString(resource, *arguments)
}
