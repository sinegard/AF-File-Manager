package com.affilemanager.app.ui.localization

import android.content.res.Configuration
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class LocalizedResourcesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun applicationAndStaleServiceContextsUseTheChosenLanguageAndPreserveFormatArguments() {
        val application = compose.activity.application
        val stale = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocale(Locale.GERMAN)
        })
        try {
            for (tag in listOf("en", "lt", "ar", "de", "en")) {
                compose.runOnUiThread { AppLanguageManager.setLanguage(compose.activity, tag) }
                compose.waitUntil(10_000) {
                    compose.activity.resources.configuration.locales[0].language == tag
                }
                val expected = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                })
                listOf(R.string.close, R.string.stop, R.string.terminal_notification_title,
                    R.string.lan_transfer_channel_name, R.string.nearby_transfer_starting_text).forEach { resource ->
                    assertEquals("Application leaked its device locale for $tag", expected.getString(resource), application.appString(resource))
                    assertEquals("Stale Service leaked its device locale for $tag", expected.getString(resource), stale.appString(resource))
                }
                if (tag == "en") assertNotEquals(stale.getString(R.string.close), stale.appString(R.string.close))
                val name = "Šiukšliadėžė – report.pdf"
                assertEquals(expected.getString(R.string.open_with_chooser_title, name), stale.appString(R.string.open_with_chooser_title, name))
            }
        } finally { compose.runOnUiThread { AppLanguageManager.setLanguage(compose.activity, "en") } }
    }
}
