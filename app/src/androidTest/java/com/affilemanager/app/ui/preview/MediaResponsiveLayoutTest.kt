package com.affilemanager.app.ui.preview

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.media.editing.MediaTestFixtures
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class MediaResponsiveLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun completePlayersKeepControlsReachableInShortWindowsAndLongTranslations() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.getExternalFilesDir("validation"), "responsive-${System.nanoTime()}").apply { check(mkdirs()) }
        val audio = File(root, "audio.wav").also { MediaTestFixtures.wav(it) }
        val video = File(root, "video.mp4").also { MediaTestFixtures.video(it, audio = false) }
        val sources = listOf(audio, video).map { PreviewSource.Local(app.graph.localFiles.toEntry(it)) }
        val source = mutableStateOf<PreviewSource>(sources[0])
        val language = mutableStateOf("en")
        val font = mutableFloatStateOf(1f)
        val short = mutableStateOf(false)
        try {
            compose.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(language.value)) }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalConfiguration provides configuration,
                    LocalDensity provides Density(density.density, font.floatValue),
                    LocalLayoutDirection provides if (language.value == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MaterialTheme {
                        Box(Modifier.size(320.dp, if (short.value) 180.dp else 490.dp)) {
                            MediaPreview(source.value, true, {}, {}, {}, emptyList())
                        }
                    }
                }
            }
            for ((index, item) in sources.withIndex()) for (tag in listOf("en", "lt", "de", "ar")) {
                for (large in listOf(false, true)) for (landscape in listOf(false, true)) {
                    compose.runOnIdle { source.value = item; language.value = tag; font.floatValue = if (large) 2f else 1f; short.value = landscape }
                    val prefix = if (index == 0) "audio" else "video"
                    compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("${prefix}_play_pause").performScrollTo().assertIsEnabled(); true }.getOrDefault(false) }
                    compose.onNodeWithTag("${prefix}_play_pause").assertIsDisplayed()
                    val backgroundAction = compose.onNodeWithTag("${prefix}_background_start")
                    backgroundAction.performScrollTo().assertIsDisplayed()
                    val fullButton = backgroundAction.getUnclippedBoundsInRoot()
                    val clippedButton = backgroundAction.fetchSemanticsNode().boundsInRoot
                    assertEquals("The whole translated button must fit its scrolling viewport: $prefix/$tag/$large/$landscape",
                        fullButton.height.value * app.resources.displayMetrics.density, clippedButton.height, 2f)
                    compose.onNodeWithTag("${prefix}_volume").performScrollTo().assertIsDisplayed()
                    val bounds = compose.onNodeWithTag("${prefix}_player").fetchSemanticsNode().boundsInRoot
                    assertTrue(bounds.width > 0f && bounds.height > 0f)
                    if (tag in listOf("en", "ar") && large && landscape) File(root, "$prefix-$tag-large-short.png").outputStream().use {
                        compose.onNodeWithTag("${prefix}_player").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        } finally { audio.delete(); video.delete() }
    }
}
