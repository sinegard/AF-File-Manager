package com.affilemanager.app.ui.preview

import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class AnimatedImagePreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun twoFrameGifUsesAnimatedDecoderAndOpensInTheNormalZoomViewport() {
        check(android.os.Build.VERSION.SDK_INT >= 28)
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixture = File(app.cacheDir, "animated-${UUID.randomUUID()}.gif")
        val gif = "47494638396101000100800000000000ffffff21ff0b4e45545343415045322e30" +
            "030100000021f904000a0000002c0000000001000100000202440100" +
            "21f904000a0000002c00000000010001000002024c01003b"
        fixture.writeBytes(gif.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        try {
            val entry = FileEntry(fixture.path, fixture.name, EntryKind.IMAGE, fixture.length(),
                fixture.lastModified(), false, true, false)
            val source = PreviewSource.Local(entry)
            assertTrue(decodePreviewImage(app, source) is DecodedPreviewImage.DrawableImage)
            assertTrue((decodePreviewImage(app, source) as DecodedPreviewImage.DrawableImage).drawable
                is AnimatedImageDrawable)
            compose.setContent { MaterialTheme {
                ImagePreview(source, canNavigate = false, onPrevious = {}, onNext = {})
            } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("animated_image_preview").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("animated_image_preview").assertIsDisplayed()
            compose.onNodeWithTag("image-zoom-viewport").assertIsDisplayed()
        } finally { fixture.delete() }
    }
}
