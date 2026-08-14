package com.affilemanager.app

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import android.os.Build
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun startupShowsFileManagerAndCurrentPermissionState() {
        assertEquals("AF File Manager", compose.activity.applicationInfo.loadLabel(compose.activity.packageManager).toString())
        compose.onNodeWithText("Failai").fetchSemanticsNode()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !Environment.isExternalStorageManager()) {
            compose.onNodeWithText("Reikia prieigos prie bendrų failų").fetchSemanticsNode()
        } else {
            assertTrue(compose.onAllNodesWithText("Reikia prieigos prie bendrų failų").fetchSemanticsNodes().isEmpty())
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Vidinė atmintis").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Failų vietos").fetchSemanticsNode()
        assertTrue(compose.onAllNodesWithText("Kairysis:").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun filesDestinationOpensLocationsAndAChoiceOpensTheActivePanel() {
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Vidinė atmintis").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Vidinė atmintis").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !viewModel.filesHomeVisible.value }
        assertTrue(compose.onAllNodesWithText("Kairysis:").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("Failai").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { viewModel.filesHomeVisible.value }
        compose.onNodeWithText("Failų vietos").fetchSemanticsNode()
    }

    @Test
    fun systemBackMovesTheActivePanelUpBeforeExiting() {
        val directory = File(compose.activity.getExternalFilesDir(null), "back-${System.nanoTime()}").apply { mkdirs() }
        try {
            val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
            val previousPath = viewModel.leftPanel.value.path
            compose.runOnUiThread {
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, directory.absolutePath)
            }
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.leftPanel.value.path == directory.canonicalPath }
            compose.waitForIdle()
            assertTrue(
                viewModel.leftPanel.value.backHistory.lastOrNull()?.let { File(it).canonicalPath == File(previousPath).canonicalPath } == true,
            )

            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

            compose.waitUntil(timeoutMillis = 5_000) {
                runCatching { File(viewModel.leftPanel.value.path).canonicalPath == File(previousPath).canonicalPath }.getOrDefault(false)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun savedConnectionCanBeEditedWithoutReenteringItsSecret() {
        val graph = (compose.activity.application as AFFileManagerApplication).graph
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val created = runBlocking {
            graph.networkProfiles.list().forEach { graph.networkProfiles.remove(it.id).getOrThrow() }
            graph.networkProfiles.save(
                NetworkProfile(
                    id = "",
                    name = "Test NAS",
                    protocol = NetworkProtocol.SFTP,
                    host = "nas.example.test",
                    port = 22,
                    username = "tester",
                    basePath = "/files",
                    allowFirstUseTrust = true,
                ),
                "kept-secret".toCharArray(),
                null,
            ).getOrThrow()
        }
        try {
            compose.runOnUiThread { viewModel.refreshProfiles() }
            compose.onNodeWithText("Ryšiai").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.networkState.value.profiles.any { it.id == created.id } }
            compose.onNodeWithContentDescription("Redaguoti jungtį").performClick()
            compose.onNodeWithText("Redaguoti jungtį").fetchSemanticsNode()
            compose.onNode(hasSetTextAction() and hasText("nas.example.test", substring = true))
                .performTextReplacement("edited.example.test")

            compose.onNodeWithText("Išsaugoti").performClick()

            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.networkState.value.profiles.singleOrNull { it.id == created.id }?.host == "edited.example.test"
            }
            val stored = runBlocking { graph.networkProfiles.list().single { it.id == created.id } }
            assertEquals("edited.example.test", stored.host)
            runBlocking {
                graph.networkProfiles.secret(created.id).getOrThrow().use { secret ->
                    assertEquals("kept-secret", secret.password.concatToString())
                }
            }
        } finally {
            runBlocking { graph.networkProfiles.remove(created.id) }
            compose.runOnUiThread { viewModel.refreshProfiles() }
        }
    }
}
