package com.affilemanager.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.archive.ArchiveEngine
import com.affilemanager.app.archive.ArchiveFormat
import com.affilemanager.app.data.DirectoryDisplayDefaults
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.data.LocalFileRepository
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProtocol
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.transfer.LanWebDavServer
import com.affilemanager.app.ui.ArchiveBrowserUiState
import com.affilemanager.app.ui.FileScrollKey
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PreviewTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID

class ArchiveReturnSourcesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sevenZRestoresThreeNestedLevelsAndBothScrollLayouts() =
        exercise(ArchiveFormat.SEVEN_Z)

    @Test fun protectedZipKeepsItsUnlockAndOwnedReturnState() =
        exercise(ArchiveFormat.ZIP, protected = true)

    @Test fun downloadedArchiveReturnsToItsOwnerAndDiscardsOnlyItsPrivateCacheOnClose() =
        exercise(ArchiveFormat.ZIP, remote = true)

    private fun exercise(format: ArchiveFormat, protected: Boolean = false, remote: Boolean = false) {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val root = File(app.cacheDir, "archive-source-${UUID.randomUUID()}").apply { check(mkdir()) }
        val sourceFolder = root.resolve("Folder/Second/Third").apply { check(mkdirs()) }
        val sources = (0 until 80).map { index -> sourceFolder.resolve("entry-${index.toString().padStart(3, '0')}.txt")
            .apply { writeText("Own archive fixture $index") } }
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val sourceHashes = sources.map(::digest)
        val extension = if (format == ArchiveFormat.SEVEN_Z) "7z" else "zip"
        val archive = root.resolve("source Š ą.$extension")
        var server: LanWebDavServer? = null
        var profile: NetworkProfile? = null
        var remoteEntry: RemoteEntry? = null
        val previousProfiles = runBlocking { app.graph.networkProfiles.list() }
        try {
            runBlocking { ArchiveEngine().create(format, archive, listOf(root.resolve("Folder")),
                if (protected) "archive-fixture-only".toCharArray() else null) }
            val original = digest(archive)
            if (remote) {
                server = LanWebDavServer(root, InetAddress.getByName("127.0.0.1"), readOnly = true, anonymous = true)
                val session = server.start()
                profile = runBlocking { app.graph.networkProfiles.save(NetworkProfile(
                    id = "archive-native-${UUID.randomUUID()}", name = "Archive fixture", protocol = NetworkProtocol.WEBDAV,
                    host = "127.0.0.1", port = session.port, username = "fixture", basePath = "/", webDavUseTls = false,
                ), charArrayOf('x')).getOrThrow() }
                compose.runOnUiThread { vm.connectNetwork(requireNotNull(profile)) }
                compose.waitUntil(15_000) { vm.networkState.value.connectedProfile?.id == profile.id &&
                    !vm.networkState.value.loading && vm.networkState.value.entries.any { it.name == archive.name } }
                remoteEntry = vm.networkState.value.entries.single { it.name == archive.name }
            }
            fun browser(): ArchiveBrowserUiState = when (val target = vm.preview.value) {
                is PreviewTarget.Archive -> target.browser
                is PreviewTarget.RemoteArchive -> target.browser
                else -> error("Expected an archive browser, not ${target?.javaClass?.simpleName}")
            }
            fun open() {
                compose.runOnUiThread {
                    if (remote) vm.openRemoteEntry(requireNotNull(remoteEntry))
                    else vm.open(LocalFileRepository(app).toEntry(archive))
                }
                if (protected) {
                    compose.waitUntil(10_000) { vm.archiveUnlock.value != null }
                    compose.runOnUiThread { vm.unlockArchive("wrong-fixture-password".toCharArray()) }
                    compose.waitUntil(10_000) { vm.archiveUnlock.value?.error != null }
                    assertNull(vm.preview.value)
                    compose.runOnUiThread { vm.unlockArchive("archive-fixture-only".toCharArray()) }
                }
                compose.waitUntil(15_000) { if (remote) vm.preview.value is PreviewTarget.RemoteArchive
                    else vm.preview.value is PreviewTarget.Archive }
            }
            for (grid in listOf(false, true)) {
                open()
                val owner = browser().id
                val cached = (vm.preview.value as? PreviewTarget.RemoteArchive)?.file?.file
                if (remote) assertArrayEquals(original, digest(requireNotNull(cached)))
                compose.runOnUiThread { vm.updateArchiveBrowser(owner) { it.copy(display = DirectoryDisplayDefaults(
                    DirectoryDisplaySettings(layoutMode = if (grid) DirectoryLayoutMode.GRID else DirectoryLayoutMode.LIST),
                    SortMode.NAME, SortDirection.ASCENDING)) } }
                for (folder in listOf("Folder", "Second", "Third")) compose.onNodeWithText(folder).performClick()
                val tag = if (grid) "archive_entries_grid" else "archive_entries_list"
                compose.onNodeWithTag(tag).performScrollToIndex(20)
                compose.onNodeWithText("entry-020.txt").assertIsDisplayed()
                compose.waitForIdle()
                val key = FileScrollKey(owner, "Folder/Second/Third", grid)
                val position = vm.fileScrollPosition(key)
                assertTrue(position.firstVisibleItemIndex > 0)
                compose.onNodeWithText("entry-020.txt").performClick()
                compose.waitUntil(10_000) { vm.preview.value is PreviewTarget.ArchiveEntry }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
                assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
                assertEquals(owner, browser().id)
                assertEquals("Folder/Second/Third", browser().path)
                assertEquals(position, vm.fileScrollPosition(key))
                compose.onNodeWithText("entry-020.txt").assertIsDisplayed()
                compose.activityRule.scenario.recreate()
                compose.onNodeWithText("entry-020.txt").assertIsDisplayed()
                assertEquals(owner, browser().id)
                assertEquals(position, vm.fileScrollPosition(key))
                compose.runOnUiThread { vm.closePreview() }
                compose.waitUntil(10_000) { vm.preview.value == null }
                if (cached != null) compose.waitUntil(10_000) { !cached.exists() }
                open()
                assertNotEquals(owner, browser().id)
                compose.onNodeWithText("Folder").assertIsDisplayed()
                compose.runOnUiThread { vm.closePreview() }
                compose.waitUntil(10_000) { vm.preview.value == null }
                assertArrayEquals(original, digest(archive))
            }
            sources.forEachIndexed { index, file -> assertArrayEquals(sourceHashes[index], digest(file)) }
        } finally {
            compose.runOnUiThread {
                repeat(2) { vm.closePreview() }
                vm.cancelArchiveUnlock()
                if (remote) vm.disconnectNetwork()
            }
            if (remote) compose.waitUntil(10_000) { vm.networkState.value.connectedProfile == null }
            server?.close()
            profile?.let { saved -> runBlocking { app.graph.networkProfiles.remove(saved.id).getOrThrow() } }
            assertEquals(previousProfiles, runBlocking { app.graph.networkProfiles.list() })
            check(root.canonicalPath.startsWith(app.cacheDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
    }
}
