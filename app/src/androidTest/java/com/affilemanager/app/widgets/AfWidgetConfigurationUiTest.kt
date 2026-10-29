package com.affilemanager.app.widgets

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import com.affilemanager.app.ui.theme.AppearanceSettings
import com.affilemanager.app.ui.components.CanonicalPathSource
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class AfWidgetConfigurationUiTest {
    @get:Rule val compose = createComposeRule()
    private fun assertFooterSeparated(language: String, density: Float) {
        val cancel = com.affilemanager.app.ui.localization.UiTranslator.translate("Atšaukti", language)
        val cancelNode = compose.onNodeWithTag("widget_cancel").assertIsDisplayed().fetchSemanticsNode()
        val saveNode = compose.onNodeWithTag("widget_save").assertIsDisplayed().fetchSemanticsNode()
        val cancelBounds = cancelNode.boundsInRoot
        val saveBounds = saveNode.boundsInRoot
        val overlapY = minOf(cancelBounds.bottom, saveBounds.bottom) - maxOf(cancelBounds.top, saveBounds.top)
        val gap = if (overlapY > 1f) {
            maxOf(cancelBounds.left, saveBounds.left) - minOf(cancelBounds.right, saveBounds.right)
        } else {
            maxOf(cancelBounds.top, saveBounds.top) - minOf(cancelBounds.bottom, saveBounds.bottom)
        }
        val minimum = (if (overlapY > 1f) 8f else 4f) * density
        System.out.println("AF_WIDGET_FOOTER $language cancel=$cancelBounds save=$saveBounds gap=$gap minimum=$minimum")
        assertTrue("Footer actions must have separate targets ($language): cancel=$cancelBounds save=$saveBounds gap=$gap minimum=$minimum",
            gap >= minimum - 1f)
        for ((label, ownerId) in listOf(cancel to cancelNode.id,
            com.affilemanager.app.ui.localization.UiTranslator.translate("Išsaugoti", language) to saveNode.id)) {
            val owner = SemanticsMatcher("the intended footer button") { it.id == ownerId }
            val text = compose.onNode(hasText(label) and hasAnyAncestor(owner), useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val visible = text.fetchSemanticsNode().boundsInRoot
            val paragraph = layouts.single()
            assertTrue("The complete footer label must be visible ($language/$label): visible=$visible paragraph=${paragraph.size}",
                visible.height >= paragraph.size.height - 1f && visible.width >= paragraph.size.width - 1f)
        }
    }
    @Test fun englishKeepsAllWidgetKindsAndSaveReachable() = exercise("en", 1f)
    @Test fun rtlLargeTextKeepsTheControlsInsideTheWindow() = exercise("ar", 2f)
    private fun exercise(language: String, scale: Float) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)); fontScale = scale }
        val localized = context.createConfigurationContext(config)
        val state = mutableStateOf(AfWidgetConfig(folder = context.cacheDir.path))
        var saved = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                AFFileManagerTheme(AppearanceSettings()) {
                    WidgetConfigurationForm(state.value, { state.value = it }, null, {}, { saved = true })
                }
            }
        }
        compose.waitUntil(5000) { !compose.onNodeWithTag("widget_title_input").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Focused] }
        compose.onNodeWithTag("widget_title_input").assertIsNotFocused()
        for (kind in AfWidgetKind.entries) {
            compose.onNodeWithTag("widget_kind_${kind.name}").performScrollTo().performClick().assertIsSelected()
            compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("widget_save").assertIsDisplayed() }.isSuccess }
            compose.onNodeWithTag("widget_save").assertIsDisplayed().assertIsEnabled()
            if (kind == AfWidgetKind.IMAGES) compose.onNodeWithText(com.affilemanager.app.ui.localization.UiTranslator.translate("Nuotraukų keitimo intervalas", language)).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("widget_save").performTouchInput { click() }
        assertTrue(saved)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir("validation"), "widget-configuration-$language.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
    @Test fun lithuanianLargeText() = exercise("lt", 2f)

    @Test fun canonicalFolderPathKeepsLogicalOrderingInRtlLocales() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val language = mutableStateOf("ar")
        val path = mutableStateOf("/وثائق/صور/ملف")
        var saves = 0
        compose.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language.value))
            }
            val localized = context.createConfigurationContext(configuration)
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalLayoutDirection provides if (language.value == "en") LayoutDirection.Ltr else LayoutDirection.Rtl) {
                AFFileManagerTheme(AppearanceSettings()) {
                    WidgetConfigurationForm(AfWidgetConfig(kind = AfWidgetKind.SHORTCUT, folder = path.value), {}, null, {}, { saves++ })
                }
            }
        }
        for (tag in listOf("en", "ar", "he", "fa", "ur")) {
            for (folder in listOf("/storage/emulated/0/Pictures", "/وثائق/صور/ملف")) {
                compose.runOnIdle { language.value = tag; path.value = folder }
                val node = compose.onNode(SemanticsMatcher.expectValue(CanonicalPathSource, folder))
                    .performScrollTo().assertIsDisplayed()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                val layout = layouts.single()
                assertEquals("The raw path must not be translated or rewritten", folder,
                    node.fetchSemanticsNode().config[CanonicalPathSource])
                val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                try {
                    File(context.getExternalFilesDir("validation"), "widget-path-$tag.png").outputStream().use {
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { image.recycle() }
                assertEquals("Canonical path order: $tag/$folder",
                    androidx.compose.ui.text.style.ResolvedTextDirection.Ltr, layout.getParagraphDirection(0))
                val separators = layout.layoutInput.text.text.indices.filter { layout.layoutInput.text.text[it] == '/' }
                separators.zipWithNext().forEach { (before, after) ->
                    val beforeLine = layout.getLineForOffset(before)
                    val afterLine = layout.getLineForOffset(after)
                    assertTrue("Path segment separators must retain their visual order: $tag/$folder/$before/$after",
                        beforeLine < afterLine || beforeLine == afterLine &&
                            layout.getBoundingBox(before).left < layout.getBoundingBox(after).left)
                }
            }
        }
        compose.onNodeWithTag("widget_save").performTouchInput { click() }
        compose.runOnIdle { assertEquals("Save action must remain usable", 1, saves) }
    }

    @Test fun allShippedLanguagesKeepEveryWidgetKindReadableAtThreeFontSizes() = checkLanguageBatch(0)
    @Test fun widgetLanguagesBatchTwo() = checkLanguageBatch(1)
    @Test fun widgetLanguagesBatchThree() = checkLanguageBatch(2)
    @Test fun widgetLanguagesBatchFour() = checkLanguageBatch(3)

    private fun checkLanguageBatch(batch: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val language = mutableStateOf("en")
        val font = mutableFloatStateOf(1f)
        val state = mutableStateOf(AfWidgetConfig(folder = "/"))
        val requested = InstrumentationRegistry.getArguments().getString("afAuditLanguages")?.split(',')
            ?: com.affilemanager.app.ui.localization.AppLanguageManager.SUPPORTED_LANGUAGE_TAGS
        val languages = requested.chunked(15).getOrNull(batch).orEmpty()
        org.junit.Assume.assumeTrue("No languages in this explicitly selected batch", languages.isNotEmpty())
        var saved = 0
        compose.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language.value)); fontScale = font.floatValue
            }
            val localized = context.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, font.floatValue),
                LocalLayoutDirection provides if (android.text.TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(language.value)) == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                key(language.value, font.floatValue) {
                    AFFileManagerTheme(AppearanceSettings()) {
                        WidgetConfigurationForm(state.value, { state.value = it }, null, {}, { saved++ })
                    }
                }
            }
        }
        for (tag in languages) for (scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { language.value = tag; font.floatValue = scale }
            for (kind in AfWidgetKind.entries) {
                val case = "$tag/$scale/$kind"
                compose.onNodeWithTag("widget_kind_${kind.name}").performScrollTo().performClick().assertIsSelected()
                compose.waitUntil(5000) { runCatching { compose.onNodeWithTag("widget_save").assertIsDisplayed() }.isSuccess }
                compose.onNodeWithTag("widget_save").assertIsDisplayed().assertIsEnabled()
                compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                    .fetchSemanticsNodes().forEach { node ->
                        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                        layouts.forEach { text ->
                            assertFalse("$case clipped: ${text.layoutInput.text}", text.multiParagraph.didExceedMaxLines ||
                                text.multiParagraph.height > text.size.height + 1f || (0 until text.lineCount).any {
                                    text.isLineEllipsized(it) || text.getLineRight(it) - text.getLineLeft(it) > text.size.width + 1f
                                })
                        }
                    }
                val before = saved
                compose.onNodeWithTag("widget_save").performTouchInput { click() }
                compose.runOnIdle { assertEquals("$case save callback", before + 1, saved) }
            }
            assertFooterSeparated(tag, context.resources.displayMetrics.density)
            System.out.println("AF_WIDGET_LAYOUT_OK $tag/$scale")
        }
    }

    @Test fun largeTextTitleAndSaveRemainReachableWithTheKeyboardOpen() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val oldFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("lt")); fontScale = 2f }
        val localized = context.createConfigurationContext(configuration)
        val state = mutableStateOf(AfWidgetConfig(folder = "/"))
        var saved = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, 2f)) {
                AFFileManagerTheme(AppearanceSettings()) {
                    WidgetConfigurationForm(state.value, { state.value = it }, null, {}, { saved = true })
                }
            }
        }
        compose.waitUntil(5000) { compose.onNodeWithTag("widget_cancel")
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused] }
        val title = com.affilemanager.app.ui.localization.UiTranslator.translate("Valdiklis", "lt")
        val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(title).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(titleLayouts)
        val expectedHeight = titleLayouts.single().size.height
        compose.onNodeWithTag("widget_title_input").performScrollTo().performClick().performTextInput("My widget")
        compose.waitForIdle()
        val keyboardDeadline = android.os.SystemClock.elapsedRealtime() + 5000
        while (automation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } &&
            android.os.SystemClock.elapsedRealtime() < keyboardDeadline) android.os.SystemClock.sleep(50)
        assertTrue("The real input method did not open", automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD })
        automation.waitForIdle(200, 3000)
        fun editableName(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable && node.text?.toString() == "My widget") return node
            for (index in 0 until node.childCount) editableName(node.getChild(index))?.let { return it }
            return null
        }
        val inputMethod = android.graphics.Rect().also { automation.windows.first { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }.getBoundsInScreen(it) }
        val nameLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("widget_title_input").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(nameLayouts) }
        fun nameBounds(): android.graphics.Rect? = automation.windows.asSequence().mapNotNull { editableName(it.root) }.firstOrNull()?.let {
            android.graphics.Rect().also(it::getBoundsInScreen)
        }
        compose.waitUntil(5000) { nameBounds()?.let { it.top >= 0 && it.bottom <= inputMethod.top && it.height() >= nameLayouts.single().size.height - 2 } == true }
        val nameBounds = requireNotNull(nameBounds())
        assertTrue("The edited name is hidden or clipped by the keyboard: $nameBounds / IME $inputMethod",
            nameBounds.top >= 0 && nameBounds.bottom <= inputMethod.top && nameBounds.height() >= nameLayouts.single().size.height - 2)
        assertFooterSeparated("lt", context.resources.displayMetrics.density)
        compose.waitForIdle()
        automation.waitForIdle(200, 3000)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { File(context.getExternalFilesDir("validation"), "widget-large-text-keyboard.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { image.recycle() }
        assertFooterSeparated("lt", context.resources.displayMetrics.density)
        fun findTitle(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == title) return node
            for (index in 0 until node.childCount) findTitle(node.getChild(index))?.let { return it }
            return null
        }
        fun titleBounds(): android.graphics.Rect? {
            // A prior density/locale case can leave Android's automation cache with
            // the old virtual node bounds. Read the current native window, not that cache.
            if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val nativeTitle = automation.windows.asSequence().mapNotNull { findTitle(it.root) }.firstOrNull()
            return nativeTitle?.let { android.graphics.Rect().also(it::getBoundsInScreen) }
        }
        // A short keyboard window may legitimately scroll the header, but it must
        // remain reachable inside the dialog rather than being panned off-window.
        val titleNode = compose.onNodeWithText(title)
        var ancestor = titleNode.fetchSemanticsNode().parent
        var scrollableHeader = false
        while (ancestor != null) {
            if (ancestor.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)) scrollableHeader = true
            ancestor = ancestor.parent
        }
        // On a pinned header Android's accessibility window may still be updating after
        // the IME animation. Do not try an impossible scroll instead of awaiting its bounds.
        if (scrollableHeader && titleBounds()?.let { it.top < 0 || it.height() < expectedHeight - 2 } != false) titleNode.performScrollTo()
        val keyboardTitleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        titleNode.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(keyboardTitleLayouts) }
        val keyboardTitle = keyboardTitleLayouts.single()
        val semanticTitleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val glyphBounds = (title.indices).map(keyboardTitle::getBoundingBox)
        System.out.println("AF_WIDGET_KEYBOARD_HEADER bounds=${titleBounds()} semantic=$semanticTitleBounds beforeHeight=$expectedHeight currentHeight=${keyboardTitle.size.height} paragraphHeight=${keyboardTitle.multiParagraph.height} glyphMin=${glyphBounds.minOf { it.top }} glyphMax=${glyphBounds.maxOf { it.bottom }} scrollable=$scrollableHeader")
        assertFalse("The current keyboard title layout clips its text", keyboardTitle.didOverflowHeight ||
            keyboardTitle.multiParagraph.height > keyboardTitle.size.height + 1f || keyboardTitle.multiParagraph.didExceedMaxLines)
        val currentExpectedHeight = keyboardTitle.size.height
        fun renderedTitleBounds(): androidx.compose.ui.geometry.Rect {
            val node = titleNode.fetchSemanticsNode()
            val coordinates = node.layoutInfo.coordinates
            val top = coordinates.localToScreen(androidx.compose.ui.geometry.Offset.Zero)
            val bottom = coordinates.localToScreen(androidx.compose.ui.geometry.Offset(node.size.width.toFloat(), node.size.height.toFloat()))
            return androidx.compose.ui.geometry.Rect(top, bottom)
        }
        // Native accessibility exposes the remaining semantics region, not always
        // the paragraph's full line-height box. Verify the actual rendered node in
        // screen pixels as well as its un-clipped layout, rather than comparing
        // those two different rectangles (notably on newer Android).
        compose.waitUntil(5000) { renderedTitleBounds().let { it.top >= 0 && it.bottom <= inputMethod.top && it.height >= currentExpectedHeight - 2 } }
        val bounds = renderedTitleBounds()
        assertTrue("Widget title was panned or clipped: $bounds, expected height $currentExpectedHeight", bounds.top >= 0 && bounds.bottom <= inputMethod.top && bounds.height >= currentExpectedHeight - 2)
        assertTrue("The title's semantics layout clips the paragraph", semanticTitleBounds.height >= currentExpectedHeight - 2)
        assertTrue("The native title is unavailable to accessibility", titleBounds()?.let { it.top >= 0 && !it.isEmpty } == true)
        val save = compose.onNodeWithTag("widget_save")
        if (runCatching { save.assertIsDisplayed() }.isFailure) save.performScrollTo()
        save.assertIsDisplayed().performTouchInput { click() }
        assertTrue(saved)
        // Dismissing the input method must restore the same usable dialog, not close it.
        if (automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(5000) { automation.windows.none { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        }
        compose.onNodeWithTag("widget_configuration").assertIsDisplayed()
        } finally { automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags } }
    }
}
