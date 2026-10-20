package com.affilemanager.app.widgets

import org.junit.Assert.*
import org.junit.Test

class AfWidgetRulesTest {
    private val base = AfWidgetConfig(folder = "/storage/emulated/0")
    @Test fun nativeWidgetControlLabelsHaveEnglishAndLithuanianTranslations() {
        val labels = mapOf("Nustatymai" to "Settings", "Atnaujinti" to "Refresh", "Ankstesnis" to "Previous",
            "Kitas" to "Next", "Leisti" to "Play", "Pauzė" to "Pause", "Sustabdyti" to "Stop")
        labels.forEach { (source, english) ->
            assertEquals(english, com.affilemanager.app.ui.localization.UiTranslator.translate(source, "en"))
            // The renderer passes the Lithuanian source label in both locales.
            // Reverse-translating "Next" is ambiguous (wizard "Toliau" vs media "Kitas").
            assertEquals(source, com.affilemanager.app.ui.localization.UiTranslator.translate(source, "lt"))
        }
    }
    @Test fun limitsAndPathValidationDoNotAdmitRemoteCommandsOrUnboundedMedia() {
        base.copy(sources = List(32) { "/storage/emulated/0/$it.jpg" }).validated()
        listOf(base.copy(sources = List(33) { "/storage/emulated/0/$it.jpg" }),
            base.copy(sources = listOf("https://example.com/file.jpg")), base.copy(sources = listOf("relative.jpg")),
            base.copy(sources = listOf("/one.jpg", "/one.jpg")), base.copy(folder = "relative"),
            base.copy(rotation = 45), base.copy(opacity = 0), base.copy(density = 400), base.copy(speed = Float.NaN),
            base.copy(index = 32), base.copy(query = "x".repeat(513)), base.copy(slideSeconds = 1)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validated() }
        }
        assertEquals(32, AfWidgetStore.MAX_WIDGETS)
    }
}
