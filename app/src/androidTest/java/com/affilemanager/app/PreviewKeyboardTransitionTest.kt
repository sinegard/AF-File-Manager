package com.affilemanager.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.media.editing.MediaTestFixtures
import com.affilemanager.app.ui.MainViewModel
import org.junit.Rule
import org.junit.Test
import java.io.File

class PreviewKeyboardTransitionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun leavingTheEditorKeepsThePlayerAndHomeControlsUncovered() {
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val folder = File(app.cacheDir, "AF-keyboard-preview-${System.nanoTime()}").apply { check(mkdir()) }
        val text = File(folder, "editor.txt").apply { writeText("unchanged editor fixture") }
        val audio = File(folder, "player.wav")
        MediaTestFixtures.wav(audio, durationMs = 30_000)
        try {
            repeat(2) { round ->
                compose.runOnUiThread { vm.open(app.graph.localFiles.toEntry(text)) }
                compose.waitUntil(10_000) { vm.fileEditState.value.text == "unchanged editor fixture" }
                compose.onNodeWithTag("full-text-editor").performTouchInput { click(center) }
                compose.waitForIdle()
                compose.runOnUiThread { vm.closePreview() }
                if (round == 0) {
                    compose.runOnUiThread { vm.open(app.graph.localFiles.toEntry(audio)) }
                    compose.waitUntil(15_000) { runCatching {
                        compose.onNodeWithTag("audio_play_pause").assertIsDisplayed(); true
                    }.getOrDefault(false) }
                    compose.runOnUiThread { vm.closePreview() }
                } else {
                    // The system keyboard closes asynchronously, outside Compose's idle clock.
                    compose.waitUntil(5_000) { runCatching {
                        compose.onNodeWithTag("nav_tools").assertIsDisplayed()
                        compose.onNodeWithTag("nav_files").assertIsDisplayed()
                        true
                    }.getOrDefault(false) }
                }
            }
        } catch (failure: Throwable) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
                File(uiAuditDirectory(app), "preview-keyboard-failure.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                image.recycle()
            }
            throw failure
        } finally {
            compose.runOnUiThread { vm.discardFileEditAndClose() }
            check(folder.deleteRecursively())
        }
    }
}
