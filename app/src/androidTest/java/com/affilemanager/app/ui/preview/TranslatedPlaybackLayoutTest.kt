package com.affilemanager.app.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.media.BackgroundPlaybackPhase
import com.affilemanager.app.media.BackgroundPlaybackState
import com.affilemanager.app.ui.components.AfActionRow
import com.affilemanager.app.ui.components.BackgroundPlaybackControls
import com.affilemanager.app.ui.localization.LText
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class TranslatedPlaybackLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longTranslationsAndLargeTextNeverClipMediaActionsAndKeepTheirCallbacks() {
        val language = mutableStateOf("en")
        val fontScale = mutableFloatStateOf(1f)
        var plays = 0
        var backgrounds = 0
        var stops = 0
        val root = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "playback-layout-captures").apply { mkdirs() }
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { setLocale(Locale.forLanguageTag(language.value)) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale.floatValue),
                LocalLayoutDirection provides if (language.value == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme {
                    Surface(Modifier.width(320.dp).testTag("translated_controls_root")) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            PlaybackControls("test", true, false, 0L, 5000L, true, false, 1f, 1f,
                                {}, {}, { plays++ }, {}, {}, {}, {}, { backgrounds++ })
                            BackgroundPlaybackControls(BackgroundPlaybackState("file:///fixture.wav", "fixture.wav", BackgroundPlaybackPhase.PLAYING), {}, { stops++ })
                            AfActionRow {
                                OutlinedButton(onClick = {}) { LText("Kopijuoti duomenis") }
                                Button(onClick = {}) { LText("Sustabdyti") }
                            }
                        }
                    }
                }
            }
        }
        listOf("en", "lt", "de", "fr", "ar").forEach { tag ->
            listOf(1f, 1.5f).forEach { scale ->
                compose.runOnIdle { language.value = tag; fontScale.floatValue = scale }
                compose.waitForIdle()
                val parent = compose.onNodeWithTag("test_playback_options").fetchSemanticsNode().boundsInRoot
                val start = compose.onNodeWithTag("test_background_start").fetchSemanticsNode().boundsInRoot
                assertTrue("$tag/$scale button escaped horizontally", start.left >= parent.left - 1 && start.right <= parent.right + 1)
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                    val results = mutableListOf<TextLayoutResult>()
                    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                    results.forEach {
                        // Compose can retain the paragraph's parent wrapping width after intrinsic
                        // measurement (e.g. a 60px timer in a 766px row). Check occupied lines instead.
                        val clipped = it.multiParagraph.didExceedMaxLines || it.multiParagraph.height > it.size.height + 1f ||
                            (0 until it.lineCount).any { line -> it.isLineEllipsized(line) ||
                                it.getLineRight(line) - it.getLineLeft(line) > it.size.width + 1f }
                        if (clipped) compose.onNodeWithTag("translated_controls_root").captureToImage().asAndroidBitmap().let { bitmap ->
                            File(root, "overflow.png").outputStream().use { stream -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) }
                            bitmap.recycle()
                        }
                        assertFalse("$tag/$scale clipped glyphs: ${it.layoutInput.text}; size=${it.size}", clipped)
                    }
                }
                compose.onNodeWithTag("test_play_pause").performScrollTo().assertIsDisplayed().performClick()
                compose.onNodeWithTag("test_background_start").performScrollTo().assertIsDisplayed().performClick()
                compose.onNodeWithTag("background_stop").performScrollTo().assertIsDisplayed().performClick()
                compose.onNodeWithTag("translated_controls_root").captureToImage().asAndroidBitmap().let { bitmap ->
                    File(root, "$tag-$scale.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        }
        compose.runOnIdle { assertEquals(10, plays); assertEquals(10, backgrounds); assertEquals(10, stops) }
    }
}
