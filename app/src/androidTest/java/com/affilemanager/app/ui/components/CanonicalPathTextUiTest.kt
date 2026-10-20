package com.affilemanager.app.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.data.FileSelectionSummary
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import com.affilemanager.app.ui.theme.AppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class CanonicalPathTextUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullPathsKeepTheirOriginalBytesAndParagraphOrder() {
        val rtl = mutableStateOf(true)
        val path = mutableStateOf("/وثائق/صور/ملف")
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl.value) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                AFFileManagerTheme(AppearanceSettings()) { CanonicalPathText(path.value) }
            }
        }
        for (direction in listOf(false, true)) for (value in listOf(
            "/storage/emulated/0/Pictures", "/وثائق/صور/ملف", "/storage/صور/2026/ملف.jpg",
            "sftp://host/وثائق/ملف.txt", "content://provider/tree/primary%3APictures", "C:\\وثائق\\صور\\ملف.txt",
        )) {
            compose.runOnIdle { rtl.value = direction; path.value = value }
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNode(SemanticsMatcher.expectValue(CanonicalPathSource, value)).assertIsDisplayed()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(value, node.fetchSemanticsNode().config[CanonicalPathSource])
            assertEquals(ResolvedTextDirection.Ltr, layouts.single().getParagraphDirection(0))
            assertSeparatorOrder(layouts.single())
        }
    }

    @Test fun bothFileInformationDialogsKeepArabicLeadingPathsCanonical() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("ar")) }
        val localized = context.createConfigurationContext(configuration)
        val selection = mutableStateOf(false)
        val path = "/وثائق/صور/ملف.txt"
        val entry = FileEntry(path, "ملف.txt", EntryKind.DOCUMENT, 13L, 0L, false, true, true)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalLayoutDirection provides LayoutDirection.Rtl) {
                AFFileManagerTheme(AppearanceSettings()) {
                    key(selection.value) {
                        if (selection.value) FileInfoDialog(listOf(entry),
                            { Result.success(FileSelectionSummary(1, 1, 0, 13L, 1, true)) }, {})
                        else FileInfoDialog(entry) {}
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { selection.value = mode }
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNode(SemanticsMatcher.expectValue(CanonicalPathSource, path)).performScrollTo().assertIsDisplayed()
            node
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(path, node.fetchSemanticsNode().config[CanonicalPathSource])
            assertEquals(ResolvedTextDirection.Ltr, layouts.single().getParagraphDirection(0))
            assertSeparatorOrder(layouts.single())
        }
    }

    private fun assertSeparatorOrder(layout: TextLayoutResult) {
        val text = layout.layoutInput.text.text
        text.indices.filter { text[it] == '/' || text[it] == '\\' }.zipWithNext().forEach { (before, after) ->
            val beforeLine = layout.getLineForOffset(before)
            val afterLine = layout.getLineForOffset(after)
            assertTrue("Path component separators must not swap their visual order: $text/$before/$after",
                beforeLine < afterLine || beforeLine == afterLine &&
                    layout.getBoundingBox(before).left < layout.getBoundingBox(after).left)
        }
    }
}
