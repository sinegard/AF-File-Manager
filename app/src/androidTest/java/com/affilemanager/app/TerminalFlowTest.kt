package com.affilemanager.app

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.terminal.TerminalLimits
import org.connectbot.terminal.VTermKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TerminalFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun useEnglish() {
        compose.runOnUiThread {
            AppLanguageManager.setLanguage(compose.activity, AppLanguageManager.ENGLISH)
        }
    }

    @Test
    fun currentFolderTerminalOpensAndBackRequiresExplicitClose() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val directory = File(compose.activity.getExternalFilesDir(null), "terminal-${System.nanoTime()}").apply { mkdirs() }
        try {
            compose.runOnUiThread {
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
                viewModel.openLocalTerminal(PanelId.LEFT)
            }
            compose.waitUntil(timeoutMillis = 10_000) { viewModel.terminalState.value.running }
            compose.onNodeWithTag("terminal-screen").fetchSemanticsNode()
            assertTrue(viewModel.terminalState.value.path.endsWith(directory.name))

            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.terminalState.value.confirmClose }
            compose.onNodeWithText("Close terminal?").fetchSemanticsNode()
            compose.onNodeWithText("Cancel").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.terminalState.value.confirmClose }
            assertTrue(viewModel.terminalState.value.running)

            compose.runOnUiThread { viewModel.requestTerminalClose() }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.terminalState.value.confirmClose }
            compose.onNodeWithText("Close").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { !viewModel.terminalState.value.visible }
            assertFalse(viewModel.terminalState.value.running)
        } finally {
            compose.runOnUiThread { viewModel.confirmTerminalClose() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun terminalSurvivesBackgroundingAndViewModelDisposal() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val application = compose.activity.application as AFFileManagerApplication
        val directory = File(compose.activity.getExternalFilesDir(null), "terminal-background-${System.nanoTime()}").apply { mkdirs() }
        try {
            compose.runOnUiThread {
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
                viewModel.openLocalTerminal(PanelId.LEFT)
            }
            compose.waitUntil(timeoutMillis = 10_000) { application.graph.terminalSessions.state.value.running }
            compose.onNodeWithTag("terminal-screen").fetchSemanticsNode()

            compose.runOnUiThread {
                compose.activity.moveTaskToBack(true)
                compose.activity.viewModelStore.clear()
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                val state = application.graph.terminalSessions.state.value
                state.visible && state.running && state.path.endsWith(directory.name)
            }

            compose.runOnUiThread {
                compose.activity.startActivity(
                    Intent(compose.activity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }

            compose.waitUntil(timeoutMillis = 10_000) {
                compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
            compose.onNodeWithTag("terminal-screen").fetchSemanticsNode()
            assertTrue(application.graph.terminalSessions.state.value.running)
        } finally {
            application.graph.terminalSessions.closeNow()
            directory.deleteRecursively()
        }
    }

    @Test
    fun largePasteDoesNotOverwhelmTheTerminalSession() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val directory = File(compose.activity.getExternalFilesDir(null), "terminal-paste-${System.nanoTime()}").apply { mkdirs() }
        try {
            compose.runOnUiThread {
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
                viewModel.openLocalTerminal(PanelId.LEFT)
            }
            compose.waitUntil(timeoutMillis = 10_000) { viewModel.terminalState.value.running }

            compose.runOnUiThread {
                viewModel.pasteIntoTerminal("x".repeat(TerminalLimits.MAX_PASTE_BYTES))
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                viewModel.terminalState.value.running && viewModel.terminalState.value.emulator != null
            }
            compose.onNodeWithTag("terminal-screen").fetchSemanticsNode()
            assertTrue(viewModel.terminalState.value.running)

            compose.runOnUiThread {
                viewModel.pasteIntoTerminal("y".repeat(TerminalLimits.MAX_PASTE_BYTES + 1))
            }
            assertTrue(viewModel.terminalState.value.running)
        } finally {
            compose.runOnUiThread { viewModel.confirmTerminalClose() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun multilinePastePreservesEveryCommandAndEnterOrder() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val directory = File(compose.activity.getExternalFilesDir(null), "terminal-multiline-${System.nanoTime()}").apply { mkdirs() }
        val output = File(directory, "pasted-lines.txt")
        val expected = "first\nsecond\nthird\nfourth\n"
        try {
            compose.runOnUiThread {
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
                viewModel.openLocalTerminal(PanelId.LEFT)
            }
            compose.waitUntil(timeoutMillis = 10_000) { viewModel.terminalState.value.running }

            compose.runOnUiThread {
                viewModel.pasteIntoTerminal(
                    "printf 'first\\n' > '${output.name}'\n" +
                        "printf 'second\\n' >> '${output.name}'\r\n" +
                        "printf 'third\\n' >> '${output.name}'\r" +
                        "printf 'fourth\\n' >> '${output.name}'",
                )
                viewModel.dispatchTerminalKey(VTermKey.ENTER)
            }

            compose.waitUntil(timeoutMillis = 10_000) {
                output.isFile && runCatching { output.readText() == expected }.getOrDefault(false)
            }
            assertEquals(expected, output.readText())
            assertTrue(viewModel.terminalState.value.running)
        } finally {
            compose.runOnUiThread { viewModel.confirmTerminalClose() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun multilineClipboardOffersPasteModesAndSingleLineWaitsForEnter() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val directory = File(compose.activity.getExternalFilesDir(null), "terminal-paste-mode-${System.nanoTime()}").apply { mkdirs() }
        val output = File(directory, "single-line.txt")
        val expected = "first second\n"
        try {
            compose.runOnUiThread {
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
                viewModel.openLocalTerminal(PanelId.LEFT)
            }
            compose.waitUntil(timeoutMillis = 10_000) { viewModel.terminalState.value.running }

            compose.runOnUiThread {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "terminal multiline test",
                        "printf 'first ' > '${output.name}'\n&& printf 'second\\n' >> '${output.name}'",
                    ),
                )
            }
            compose.onNodeWithTag("terminal-paste").performClick()
            compose.onNodeWithText("Paste multiline text?").fetchSemanticsNode()
            compose.onNodeWithTag("terminal-paste-lines").fetchSemanticsNode()
            compose.onNodeWithText("Paste as 1 line").fetchSemanticsNode()
            compose.onNodeWithTag("terminal-paste-single-line").performClick()

            SystemClock.sleep(500)
            assertFalse(output.exists())
            compose.runOnUiThread { viewModel.dispatchTerminalKey(VTermKey.ENTER) }
            compose.waitUntil(timeoutMillis = 10_000) {
                output.isFile && runCatching { output.readText() == expected }.getOrDefault(false)
            }
            assertEquals(expected, output.readText())
        } finally {
            compose.runOnUiThread { viewModel.confirmTerminalClose() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun terminalOffersTextSelectionAndCopiesTheLastOutput() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val application = compose.activity.application as AFFileManagerApplication
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        val directory = File(compose.activity.getExternalFilesDir(null), "terminal-copy-${System.nanoTime()}").apply { mkdirs() }
        val marker = "AF_LAST_OUTPUT_${System.nanoTime()}"
        try {
            compose.runOnUiThread {
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
                viewModel.openLocalTerminal(PanelId.LEFT)
            }
            compose.waitUntil(timeoutMillis = 10_000) { viewModel.terminalState.value.running }
            compose.onNodeWithTag("terminal-select-text").fetchSemanticsNode()
            compose.onNodeWithTag("terminal-copy-selection").fetchSemanticsNode()
            compose.onNodeWithTag("terminal-canvas").fetchSemanticsNode()

            compose.runOnUiThread {
                clipboard.clearPrimaryClip()
                viewModel.pasteIntoTerminal("printf '$marker\\n'\r")
            }
            // A prompt or a partial echo can be copyable before the command's answer arrives.
            // Wait for this command's output, then exercise the real Copy last output button.
            compose.waitUntil(timeoutMillis = 10_000) {
                application.graph.terminalSessions.lastCommandOutput()?.text?.contains(marker) == true
            }

            compose.onNodeWithTag("terminal-copy-last").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.contains(marker) == true
            }
        } finally {
            application.graph.terminalSessions.closeNow()
            directory.deleteRecursively()
        }
    }
}
