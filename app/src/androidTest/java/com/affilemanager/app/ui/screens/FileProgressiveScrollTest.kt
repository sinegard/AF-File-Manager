package com.affilemanager.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.FileScrollKey
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.PanelUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FileProgressiveScrollTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun cleanup() { compose.runOnUiThread { store.clear() } }

    @Test fun listNewLocationAfterEarlierRefreshStartsAtActualFirstItem() = checkProgressiveTop(false, false)
    @Test fun gridNewLocationAfterEarlierRefreshStartsAtActualFirstItem() = checkProgressiveTop(true, false)
    @Test fun listExplicitRefreshWaitsForFinalOrdering() = checkProgressiveTop(false, true)
    @Test fun gridExplicitRefreshWaitsForFinalOrdering() = checkProgressiveTop(true, true)
    @Test fun listHistoryRestoresPositionAfterAnEarlierRefresh() = checkHistory(false)
    @Test fun gridHistoryRestoresPositionAfterAnEarlierRefresh() = checkHistory(true)

    private fun entry(name: String) = FileEntry("/scroll-fixture/$name", name, EntryKind.DIRECTORY, 0, 1, false, true, true)

    private fun checkProgressiveTop(grid: Boolean, refresh: Boolean) {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<AFFileManagerApplication>())
        store.put("test", vm)
        val key = FileScrollKey("test", "/scroll-fixture", grid)
        val android = entry("Android")
        val state = mutableStateOf(PanelUiState(path = key.path, entries = listOf(android), grid = grid,
            loading = !refresh, scrollToTopRequest = 5))
        compose.setContent { MaterialTheme {
            if (grid) FileGrid(PanelId.LEFT, state.value, emptyMap(), emptySet(), key, vm, {}, {}, {}, {}, {})
            else FileList(PanelId.LEFT, state.value, emptyMap(), emptySet(), key, vm, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Android").assertIsDisplayed()
        if (refresh) compose.runOnIdle { state.value = state.value.copy(loading = true, scrollToTopRequest = 6) }
        compose.waitForIdle()
        val finalEntries = (0 until 48).map { entry(".first-${it.toString().padStart(2, '0')}") } + android
        compose.runOnIdle { state.value = state.value.copy(entries = finalEntries, loading = false) }
        compose.waitForIdle()
        compose.onNodeWithText(".first-00").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, vm.fileScrollPosition(key).firstVisibleItemIndex) }
    }

    private fun checkHistory(grid: Boolean) {
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<AFFileManagerApplication>())
        store.put("test", vm)
        val key = FileScrollKey("history", "/scroll-fixture", grid)
        vm.saveFileScrollPosition(key, 18, 0)
        val entries = (0 until 60).map { entry("entry-${it.toString().padStart(2, '0')}") }
        val state = PanelUiState(path = key.path, entries = entries, grid = grid, loading = false, scrollToTopRequest = 7)
        compose.setContent { MaterialTheme {
            if (grid) FileGrid(PanelId.LEFT, state, emptyMap(), emptySet(), key, vm, {}, {}, {}, {}, {})
            else FileList(PanelId.LEFT, state, emptyMap(), emptySet(), key, vm, {}, {}, {}, {}, {})
        } }
        compose.waitForIdle()
        compose.onNodeWithText("entry-18").assertIsDisplayed()
        compose.runOnIdle { assertEquals(18, vm.fileScrollPosition(key).firstVisibleItemIndex) }
    }
}
