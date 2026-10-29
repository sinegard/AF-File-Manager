package com.affilemanager.app.ui.theme

import android.graphics.Bitmap
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.uiAuditDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class AlertDialogKeyboardSafetyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultDialogKeepsItsActionAboveTheRealKeyboard() = checkKeyboard(true)
    @Test fun explicitEdgeToEdgeDialogKeepsItsActionAboveTheRealKeyboard() = checkKeyboard(false)

    private fun checkKeyboard(fits: Boolean) {
        lateinit var view: View
        lateinit var window: Window
        var confirmed = 0
        compose.setContent {
            AFFileManagerTheme {
                var first by remember { mutableStateOf("") }
                var second by remember { mutableStateOf("") }
                AfAlertDialog(
                    onDismissRequest = {},
                    properties = DialogProperties(decorFitsSystemWindows = fits),
                    title = { Text("Encrypt document.txt") },
                    text = {
                        view = LocalView.current
                        window = requireNotNull((view.parent as? DialogWindowProvider)?.window)
                        Column {
                            Text("At least 8 characters. A lost passphrase cannot be recovered.")
                            OutlinedTextField(first, { first = it }, Modifier.testTag("ime_first"),
                                label = { Text("Passphrase") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation())
                            OutlinedTextField(second, { second = it }, Modifier.testTag("ime_second"),
                                label = { Text("Repeat") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation())
                        }
                    },
                    confirmButton = { TextButton({ confirmed++ }, Modifier.testTag("ime_confirm")) { Text("Encrypt") } },
                )
            }
        }
        try {
            compose.onNodeWithTag("ime_first").performClick().performTextInput("public-fixture")
            compose.waitUntil(5_000) {
                ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            compose.waitForIdle()
            if (android.os.Build.VERSION.SDK_INT < 30) {
                assertEquals("A legacy dialog with input must request resize instead of unspecified/pan",
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                    window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
            }
            compose.onNodeWithTag("ime_confirm").assertIsDisplayed().performTouchInput { click() }
            compose.runOnIdle { assertEquals(1, confirmed) }
        } finally {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = uiAuditDirectory(instrumentation.targetContext)
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(directory, "alert-ime-$fits.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { bitmap.recycle() }
            File(directory, "alert-ime-$fits.json").writeText(JSONObject()
                .put("api", android.os.Build.VERSION.SDK_INT).put("requestedDecorFits", fits)
                .put("softInputMode", window.attributes.softInputMode)
                .put("imeBottom", ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom)
                .put("confirmed", confirmed).toString(2))
        }
    }
}
