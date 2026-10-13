package com.affilemanager.app.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.affilemanager.app.model.FileEntry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EditSaveAsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun saveWaitsForTheInitialListingAndItsFinalLayout() {
        val listing = CompletableDeferred<Result<List<FileEntry>>>()
        var saved: Pair<String, String>? = null
        compose.setContent { MaterialTheme {
            EditSaveAsDialog("copy.txt", "/storage/emulated/0/Download", null, null,
                loadLocalDirectory = { listing.await() },
                loadRemoteDirectory = { error("No server requested") },
                onSaveLocal = { path, name -> saved = path to name },
                onSaveRemote = { _, _ -> error("No server requested") },
                onOpenSystemPicker = {}, onDismiss = {})
        } }
        compose.onNodeWithText("Save here").assertIsNotEnabled()
        assertEquals(null, saved)
        listing.complete(Result.success(emptyList()))
        compose.onNodeWithText("This folder has no subfolders").assertIsDisplayed()
        compose.onNodeWithText("Save here").assertIsEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals("/storage/emulated/0/Download" to "copy.txt", saved) }
    }

    @Test fun proposedNameDoesNotOpenTheKeyboardButCanStillBeEdited() {
        var saved: String? = null
        compose.setContent { MaterialTheme {
            EditSaveAsDialog("copy.txt", "/storage/emulated/0/Download", null, null,
                loadLocalDirectory = { Result.success(emptyList()) },
                loadRemoteDirectory = { error("No server requested") },
                onSaveLocal = { _, name -> saved = name },
                onSaveRemote = { _, _ -> error("No server requested") },
                onOpenSystemPicker = {}, onDismiss = {})
        } }
        val test = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val access = test.uiAutomation.serviceInfo
        access.flags = access.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        test.uiAutomation.serviceInfo = access
        compose.waitUntil(5_000) { !compose.onNodeWithTag("save_as_filename").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Focused] && test.uiAutomation.windows.none {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } }
        compose.onNodeWithTag("save_as_filename").assertIsNotFocused().performTouchInput { click() }
        compose.waitUntil(5_000) { test.uiAutomation.windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } }
        compose.onNodeWithTag("save_as_filename").performTextReplacement("renamed.txt")
        compose.onNodeWithTag("save_as_filename").assertIsFocused()
        check(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(5_000) { test.uiAutomation.windows.none {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        } }
        compose.onNodeWithText("Save here").assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals("renamed.txt", saved) }
    }

    @Test fun unreadablePhoneOrServerFoldersCannotBeSavedInto() {
        var saves = 0
        compose.setContent { MaterialTheme {
            EditSaveAsDialog("copy.txt", "/missing", "/missing", "Fixture server",
                loadLocalDirectory = { Result.failure(IllegalStateException("Tai nėra aplankas")) },
                loadRemoteDirectory = { Result.failure(IllegalStateException("Tai nėra aplankas")) },
                onSaveLocal = { _, _ -> saves++ }, onSaveRemote = { _, _ -> saves++ },
                onOpenSystemPicker = {}, onDismiss = {})
        } }
        compose.onNodeWithText("This is not a folder").assertIsDisplayed()
        compose.onNodeWithText("Save here").assertIsNotEnabled()
        compose.onNodeWithText("Server").performClick()
        compose.onNodeWithText("This is not a folder").assertIsDisplayed()
        compose.onNodeWithText("Save here").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, saves) }
    }
}
