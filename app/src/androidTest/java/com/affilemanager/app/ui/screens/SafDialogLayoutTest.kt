package com.affilemanager.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.ui.components.AfModalDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SafDialogLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun createUsesSharedHeaderAndKeyboardDoesNotHideActions() {
        val created = mutableListOf<String>()
        var dismissed = 0
        compose.setContent { MaterialTheme {
            SafCreateDialog({ dismissed++ }, { created += "folder:$it" }, { created += "file:$it" })
        } }
        compose.onNodeWithText("Folder").assertIsNotEnabled()
        compose.onNodeWithText("Name").performClick().performTextInput("example.txt")
        capture("saf-create-layout")
        reachable(compose.onNodeWithText("Folder")).assertIsEnabled()
        reachable(compose.onNodeWithText("File")).assertIsEnabled()
        capture("saf-create-layout")
        compose.onNodeWithText("File").performClick()
        compose.runOnIdle { assertEquals(listOf("file:example.txt"), created) }
        reachable(compose.onNodeWithText("Cancel")).performClick()
        compose.runOnIdle { assertEquals(1, dismissed); assertEquals(1, created.size) }
        reachable(compose.onNodeWithContentDescription("Close"))
    }

    @Test fun renameSharesDialogWidthAndCancelNeverConfirms() {
        var confirmed = false
        var dismissed = false
        compose.setContent { MaterialTheme {
            SafNameDialog("Pervadinti", "very-long-file-name.txt", "Pervadinti", { dismissed = true }, { confirmed = true })
        } }
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
        compose.onNodeWithText("Name").performClick()
        capture("saf-rename-layout")
        reachable(compose.onNodeWithText("Cancel")).performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(confirmed) }
    }

    @Test fun shortDialogKeepsNestedListBoundedAndFooterReachable() {
        var dismissed = false
        compose.setContent { MaterialTheme {
            AfModalDialog(
                title = "List", translateTitle = false, icon = Icons.Rounded.Folder,
                onDismissRequest = { dismissed = true }, expandedContent = true,
                modifier = Modifier.testTag("bounded_dialog"),
                actions = { TextButton(onClick = { dismissed = true }) { Text("Done") } },
            ) {
                LazyColumn(Modifier.fillMaxSize().testTag("bounded_dialog_list")) {
                    item { OutlinedTextField("", {}, label = { Text("List filter") }, singleLine = true) }
                    items(50) { Text("Row $it") }
                }
            }
        } }
        compose.onNodeWithText("List filter").performClick()
        compose.onNodeWithTag("bounded_dialog_list").performScrollToIndex(50)
        // Scroll the outer short-dialog viewport as well as the nested lazy list.
        reachable(compose.onNodeWithText("Done"))
        reachable(compose.onNodeWithText("Row 49"))
        reachable(compose.onNodeWithText("Done")).performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    private fun reachable(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        // With a tall landscape IME the dialog itself scrolls; controls must remain
        // reachable without dismissing the keyboard or truncating their labels.
        if (!node.isDisplayed()) node.performScrollTo()
        return node.assertIsDisplayed()
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = requireNotNull(context.getExternalFilesDir("validation"))
        val width = context.resources.displayMetrics.widthPixels
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(root, "$name-full-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithTag(if (name.startsWith("saf-create")) "saf_create_dialog" else "saf_name_dialog")
            .captureToImage().asAndroidBitmap().let { bitmap ->
            File(root, "$name-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
