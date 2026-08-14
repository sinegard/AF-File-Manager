package com.affilemanager.app.data

import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspaceSessionRepositoryTest {
    @Test
    fun tabsHistoryLocksAndClosedTabsSurviveReload() = withRepository { repository, directory ->
        val leftPath = File(directory, "left").apply { mkdir() }.absolutePath
        val rightPath = File(directory, "right").apply { mkdir() }.absolutePath
        val first = WorkspaceTab(
            id = "left-1",
            path = leftPath,
            locked = true,
            backHistory = listOf(directory.absolutePath),
            sortMode = SortMode.MODIFIED,
            sortDirection = SortDirection.DESCENDING,
            includeHidden = true,
            grid = true,
        )
        val second = WorkspaceTab(id = "left-2", path = rightPath)
        val closed = WorkspaceTab(id = "closed-1", path = directory.absolutePath)
        val right = WorkspaceTab(id = "right-1", path = rightPath)
        repository.save(
            WorkspaceSession(
                left = PanelWorkspace(listOf(first, second), second.id, listOf(closed)),
                right = PanelWorkspace(listOf(right), right.id),
            ),
        )

        val restored = repository.load("fallback-left", "fallback-right")

        assertEquals("left-2", restored.left.activeTabId)
        assertTrue(restored.left.tabs.first().locked)
        assertEquals(SortMode.MODIFIED, restored.left.tabs.first().sortMode)
        assertEquals(SortDirection.DESCENDING, restored.left.tabs.first().sortDirection)
        assertTrue(restored.left.tabs.first().includeHidden)
        assertTrue(restored.left.tabs.first().grid)
        assertEquals(listOf(directory.absolutePath), restored.left.tabs.first().backHistory)
        assertEquals(listOf("closed-1"), restored.left.closedTabs.map(WorkspaceTab::id))
    }

    @Test
    fun corruptSessionFallsBackAndIsQuarantined() = withRepository { repository, directory ->
        val storage = File(directory, "workspace_session_v1.json").apply { writeText("{broken") }

        val restored = repository.load(directory.absolutePath, directory.absolutePath)

        assertEquals(1, restored.left.tabs.size)
        assertFalse(storage.exists())
        assertTrue(directory.listFiles().orEmpty().any { it.name.startsWith("workspace_session_v1.") && it.name.endsWith(".corrupt") })
    }

    @Test
    fun legacySessionKeepsWorkspaceButResetsSortToDesktopDefaultOnce() = withRepository { repository, directory ->
        val leftPath = File(directory, "left").apply { mkdir() }.absolutePath
        val rightPath = File(directory, "right").apply { mkdir() }.absolutePath
        val legacy = JSONObject()
            .put("schemaVersion", 1)
            .put("left", legacyPanel("left-legacy", leftPath, SortMode.MODIFIED, SortDirection.DESCENDING))
            .put("right", legacyPanel("right-legacy", rightPath, SortMode.SIZE, SortDirection.DESCENDING))
        File(directory, "workspace_session_v1.json").writeText(legacy.toString(), Charsets.UTF_8)

        val migrated = repository.load("fallback-left", "fallback-right")

        assertEquals(2, migrated.schemaVersion)
        assertEquals(leftPath, migrated.left.activeTab.path)
        assertEquals(rightPath, migrated.right.activeTab.path)
        assertEquals(SortMode.NAME, migrated.left.activeTab.sortMode)
        assertEquals(SortDirection.ASCENDING, migrated.left.activeTab.sortDirection)
        assertEquals(SortMode.NAME, migrated.right.activeTab.sortMode)
        assertEquals(SortDirection.ASCENDING, migrated.right.activeTab.sortDirection)

        val persisted = JSONObject(File(directory, "workspace_session_v1.json").readText(Charsets.UTF_8))
        assertEquals(2, persisted.getInt("schemaVersion"))
    }

    private fun legacyPanel(id: String, path: String, mode: SortMode, direction: SortDirection): JSONObject {
        val tab = JSONObject()
            .put("id", id)
            .put("path", path)
            .put("locked", false)
            .put("backHistory", JSONArray())
            .put("forwardHistory", JSONArray())
            .put("sortMode", mode.name)
            .put("sortDirection", direction.name)
            .put("includeHidden", false)
            .put("grid", false)
        return JSONObject()
            .put("tabs", JSONArray().put(tab))
            .put("activeTabId", id)
            .put("closedTabs", JSONArray())
    }

    private fun withRepository(block: (WorkspaceSessionRepository, File) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(application.cacheDir, "workspace-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(application) {
            override fun getFilesDir(): File = directory
        }
        try {
            block(WorkspaceSessionRepository(context), directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
