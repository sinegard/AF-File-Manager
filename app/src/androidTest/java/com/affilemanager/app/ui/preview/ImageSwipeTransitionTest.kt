package com.affilemanager.app.ui.preview

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ImageSwipeTransitionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun horizontalSwipeAnimatesOnlyTheIncomingImageAndANewTouchCancelsIt() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val files = (0..1).map { index ->
            File(app.filesDir, "swipe-${System.nanoTime()}-$index.png").also { file ->
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (index == 0) Color.GREEN else Color.BLUE)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
            }
        }
        val sources = files.map { PreviewSource.Local(app.graph.localFiles.toEntry(it)) }
        val source = mutableStateOf<PreviewSource>(sources[0])
        var callbacks = 0
        try {
            compose.setContent { MaterialTheme { ImagePreview(source.value, true,
                { callbacks++; source.value = sources[0] }, { callbacks++; source.value = sources[1] }) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("image-zoom-viewport").fetchSemanticsNodes().isNotEmpty() }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("image-zoom-viewport").performTouchInput { swipeLeft() }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                compose.onAllNodesWithContentDescription(files[1].name).fetchSemanticsNodes().isNotEmpty()
            }
            compose.mainClock.advanceTimeByFrame()
            fun offset() = compose.onNodeWithTag("image-zoom-viewport").fetchSemanticsNode().config[ImageSwipeOffsetKey]
            assertEquals(1, callbacks)
            val beginning = offset()
            assertTrue("Incoming image must have a visible transition", beginning > 0f)
            compose.mainClock.advanceTimeBy(80)
            assertTrue(offset() in 0f..beginning && offset() < beginning)
            compose.onNodeWithTag("image-zoom-viewport").performTouchInput { down(center); up() }
            assertEquals(0f, offset(), .001f)
            compose.mainClock.advanceTimeBy(300)
            assertEquals(1, callbacks)
        } finally { compose.mainClock.autoAdvance = true; files.forEach { it.delete() } }
    }
}
