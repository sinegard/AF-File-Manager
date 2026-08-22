package com.affilemanager.app

import android.content.Intent
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
}
