package com.affilemanager.app.ui.components

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.text.InputType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import com.affilemanager.app.ui.theme.AfAlertDialog
import com.affilemanager.app.uiAuditDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class PasswordKeyboardPolicyAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun androidReceivesPasswordTypeWithoutAutocorrect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        compose.setContent {
            AFFileManagerTheme {
                var value by remember { mutableStateOf("") }
                AfAlertDialog({}, confirmButton = {}, title = { Text("Public keyboard fixture") }, text = {
                    OutlinedTextField(value, { value = it }, Modifier.testTag("secure_ime_input"),
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = passwordKeyboardOptions)
                })
            }
        }
        compose.onNodeWithTag("secure_ime_input").performClick().performTextInput("public-fixture")
        var actual: Int? = null
        compose.waitUntil(5_000) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method")
            val lines = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readLines() }
            actual = lines.indices.asSequence().filter { index ->
                listOf("mCurAttribute", "mCurEditorInfo", "mCurrentTextBoxAttribute").any(lines[index]::contains)
            }.mapNotNull { index ->
                val section = lines.subList(index, (index + 12).coerceAtMost(lines.size)).joinToString("\n")
                if (!section.contains("packageName=${instrumentation.targetContext.packageName}")) null
                else Regex("inputType=0x([0-9a-fA-F]+)").find(section)?.groupValues?.get(1)?.toInt(16)
            }.firstOrNull { it and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT }
            actual != null
        }
        val inputType = requireNotNull(actual)
        val directory = uiAuditDirectory(instrumentation.targetContext)
        File(directory, "password-ime-policy.json").writeText(JSONObject()
            .put("api", android.os.Build.VERSION.SDK_INT).put("inputTypeHex", inputType.toString(16))
            .put("targetPackage", instrumentation.targetContext.packageName).toString(2))
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "password-ime-policy.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(0, inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
    }
}
