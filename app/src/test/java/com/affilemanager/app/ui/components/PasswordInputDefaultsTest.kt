package com.affilemanager.app.ui.components

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File

class PasswordInputDefaultsTest {
    @Test fun secretsAreNotPresentedToTheImeAsOrdinaryAutocorrectedText() {
        assertEquals(KeyboardType.Password, passwordKeyboardOptions.keyboardType)
        assertEquals(false, passwordKeyboardOptions.autoCorrectEnabled)
    }

    @Test fun everyMaskedPasswordInputUsesTheSharedImePolicy() {
        val sources = File("src/main/java/com/affilemanager/app/ui")
        assertTrue("Run from the app module, with real production sources", sources.isDirectory)
        var inspected = 0
        sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { source ->
            val lines = source.readLines()
            lines.forEachIndexed { index, line ->
                if (line.contains("visualTransformation = PasswordVisualTransformation()")) {
                    inspected++
                    val field = lines.subList((index - 5).coerceAtLeast(0), (index + 6).coerceAtMost(lines.size)).joinToString("\n")
                    assertTrue("${source.name}:${index + 1} masks text but does not protect its IME type",
                        field.contains("keyboardOptions = passwordKeyboardOptions"))
                }
            }
        }
        assertTrue("The known password-field inventory was not examined", inspected >= 10)
    }
}
