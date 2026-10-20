package com.affilemanager.app.ui.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.data.LocalFileRepository
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ImageDoubleTapAnimationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun doubleTapAnimatesThroughIntermediateScaleAndKeepsTheTappedImagePoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "zoom-focus-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        for (x in 0 until 512) for (y in 0 until 512) bitmap.setPixel(x, y, Color.rgb(x / 2, 100, 200))
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            compose.setContent { AFFileManagerTheme { ImagePreview(PreviewSource.Local(LocalFileRepository(context).toEntry(source)), false, {}, {}) } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("image-zoom-viewport").fetchSemanticsNodes().isNotEmpty() }
            val viewport = compose.onNodeWithTag("image-zoom-viewport")
            val imageBefore = viewport.captureToImage().toPixelMap()
            val x = (imageBefore.width * .7f).toInt()
            val y = imageBefore.height / 2
            val focusBefore = imageBefore[x, y]
            compose.mainClock.autoAdvance = false
            viewport.performTouchInput { doubleClick(Offset(x.toFloat(), y.toFloat())) }
            compose.mainClock.advanceTimeBy(80)
            val scale = compose.onNodeWithTag("zoom-level").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
            assertFalse("No intermediate zoom frame: $scale", scale.contains("100") || scale.contains("200"))
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("200 %").assertExists()
            val focusAfter = viewport.captureToImage().toPixelMap()[x, y]
            assertTrue("Tapped point moved: $focusBefore -> $focusAfter", kotlin.math.abs(focusBefore.red - focusAfter.red) < .02f)
            viewport.performTouchInput { doubleClick() }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText("100 %").assertExists()
        } finally { compose.mainClock.autoAdvance = true; source.delete() }
    }
}
