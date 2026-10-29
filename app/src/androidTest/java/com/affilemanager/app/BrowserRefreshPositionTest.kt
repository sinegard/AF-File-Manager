package com.affilemanager.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class BrowserRefreshPositionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun listMenuRefreshKeepsTheVisibleItem() = checkRefresh(false)
    @Test fun gridMenuRefreshKeepsTheVisibleItem() = checkRefresh(true)
    private fun checkRefresh(grid: Boolean) {
        val app = compose.activity.application as AFFileManagerApplication
        val root = File(app.getExternalFilesDir("validation"), "refresh-${System.nanoTime()}").apply { check(mkdirs()) }
        repeat(180) { File(root, "item-${it.toString().padStart(3, '0')}.txt").writeText("owned fixture") }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread { vm.activatePanel(PanelId.LEFT); vm.navigate(PanelId.LEFT, root.path) }
            compose.waitUntil(10_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.entries.size == 180 }
            compose.runOnUiThread { if (vm.leftPanel.value.grid != grid) vm.toggleGrid(PanelId.LEFT) }
            val tag = if (grid) "file_grid_LEFT" else "file_list_LEFT"
            compose.onNodeWithTag(tag).performScrollToIndex(60)
            compose.onNodeWithText("item-060.txt").assertIsDisplayed()
            val request = vm.leftPanel.value.scrollToTopRequest
            compose.onNodeWithContentDescription("Folder actions").performClick()
            compose.onNodeWithText("Refresh").performScrollTo().performClick()
            compose.waitUntil(10_000) { !vm.leftPanel.value.loading }
            compose.onNodeWithText("item-060.txt").assertIsDisplayed()
            assertEquals(request, vm.leftPanel.value.scrollToTopRequest)
        } finally {
            compose.runOnUiThread { vm.navigate(PanelId.LEFT, root.parentFile!!.path) }
            check(root.deleteRecursively())
        }
    }
}
