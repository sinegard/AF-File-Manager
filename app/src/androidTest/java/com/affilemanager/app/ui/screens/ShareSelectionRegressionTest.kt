package com.affilemanager.app.ui.screens

import android.app.Instrumentation
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.data.FileCategory
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ShareSelectionRegressionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun localListAndGridShareTwoActualFilesWithoutScrollingTheActions() = exercise(false)
    @Test fun quickLocationListAndGridShareTwoActualFilesWithoutScrollingTheActions() = exercise(true)

    private fun exercise(category: Boolean) {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AF-share-ui-${System.nanoTime()}")
        check(root.mkdirs())
        val files = listOf(File(root, "first.txt"), File(root, "second.txt"))
        files.forEachIndexed { index, file -> file.writeText("original-$index") }
        val indexed = mutableListOf<Uri>()
        val latch = CountDownLatch(files.size)
        MediaScannerConnection.scanFile(app, files.map { it.path }.toTypedArray(), arrayOf("text/plain", "text/plain")) { _, uri ->
            synchronized(indexed) { if (uri != null) indexed.add(uri) }; latch.countDown()
        }
        val store = ViewModelStore()
        val vm = MainViewModel(app).also { store.put("share", it) }
        val test = InstrumentationRegistry.getInstrumentation()
        val seen = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent?): Instrumentation.ActivityResult? {
                if (intent?.action != Intent.ACTION_CHOOSER) return null
                @Suppress("DEPRECATION") val send = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (send?.action != Intent.ACTION_SEND_MULTIPLE) return null
                seen.set(send)
                return null // Observe the intent; let Android open its actual chooser.
            }
        }
        test.addMonitor(monitor)
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS))
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    MaterialTheme { androidx.compose.foundation.layout.Box(Modifier.width(320.dp)) {
                        FilesScreen(vm, PaddingValues(0.dp), true, {}, {}, {})
                    } }
                }
            }
            compose.runOnIdle {
                if (category) vm.openFileCategory(FileCategory.DOCUMENTS) else vm.navigate(PanelId.LEFT, root.path)
            }
            compose.waitUntil(20_000) {
                val entries = if (category) vm.fileCategory.value.entries else vm.leftPanel.value.entries
                files.all { file -> entries.any { it.absolutePath == file.path } }
            }
            // Copy more adds another action to the dock, the common overflow case.
            compose.runOnIdle { vm.copyEntries(listOf(files.first().path), move = false) }
            compose.waitUntil(5_000) { vm.clipboard.value != null }
            for (grid in listOf(false, true)) {
                seen.set(null)
                compose.runOnIdle {
                    if (category) {
                        if (vm.fileCategory.value.grid != grid) vm.toggleFileCategoryLayout()
                    } else {
                        if (vm.leftPanel.value.grid != grid) vm.toggleGrid(PanelId.LEFT)
                    }
                }
                compose.waitForIdle()
                compose.waitUntil(10_000) { if (category) !vm.fileCategory.value.loading else !vm.leftPanel.value.loading }
                compose.runOnIdle { files.forEach {
                    if (category) vm.toggleFileCategorySelection(it.path) else vm.toggleSelection(PanelId.LEFT, it.path)
                } }
                val share = compose.onNodeWithTag(if (category) "category_share" else "selection_share_local")
                share.assertIsDisplayed().assertIsEnabled()
                val bounds = share.getUnclippedBoundsInRoot()
                // Material's visible icon button is40dp; its expanded touch target is48dp.
                share.assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
                assertEquals("Share is visually clipped", bounds, share.getBoundsInRoot())
                assertTrue("Share bounds $bounds", bounds.left >= 0.dp && bounds.right <= 320.dp)
                test.uiAutomation.waitForIdle(200, 3000)
                val image = requireNotNull(test.uiAutomation.takeScreenshot())
                try { File(uiAuditDirectory(app), "share-${if (category) "category" else "local"}-$grid.png").outputStream().use {
                    check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { image.recycle() }
                share.performTouchInput { click() }
                compose.waitUntil(15_000) { seen.get() != null }
                val send = requireNotNull(seen.get())
                @Suppress("DEPRECATION") val uris = requireNotNull(send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
                assertEquals(2, uris.distinct().size)
                assertEquals(2, send.clipData!!.itemCount)
                assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertTrue(uris.all { it.scheme == "content" && it.authority == "${app.packageName}.files" })
                assertEquals(setOf("original-0", "original-1"), uris.map { uri ->
                    app.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                }.toSet())
                files.forEachIndexed { index, file -> assertEquals("original-$index", file.readText()) }
                val deadline = android.os.SystemClock.uptimeMillis() + 10_000
                while (test.uiAutomation.rootInActiveWindow?.packageName?.toString() !in setOf("android", "com.android.intentresolver") &&
                    android.os.SystemClock.uptimeMillis() < deadline) android.os.SystemClock.sleep(50)
                assertTrue("Android chooser did not open", test.uiAutomation.rootInActiveWindow?.packageName?.toString() in
                    setOf("android", "com.android.intentresolver"))
                test.uiAutomation.waitForIdle(500, 3000)
                android.os.SystemClock.sleep(300) // Capture the settled legacy chooser, not its dimming animation.
                assertTrue(test.uiAutomation.rootInActiveWindow?.packageName?.toString() in setOf("android", "com.android.intentresolver"))
                val chooser = requireNotNull(test.uiAutomation.takeScreenshot())
                try { File(uiAuditDirectory(app), "chooser-$category-$grid.png").outputStream().use {
                    check(chooser.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { chooser.recycle() }
                assertTrue(test.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                compose.waitUntil(5_000) { (if (category) vm.fileCategory.value.selectedPaths else vm.leftPanel.value.selectedPaths).isEmpty() }
            }
        } finally {
            test.removeMonitor(monitor)
            compose.runOnIdle { vm.clearAfClipboard(); store.clear() }
            indexed.forEach { app.contentResolver.delete(it, null, null) }
            files.forEach { if (it.exists()) check(it.delete()) }
            check(root.delete())
        }
    }
}
