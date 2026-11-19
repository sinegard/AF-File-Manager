package com.affilemanager.app.ui.screens

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.network.RemoteCopyEngine
import com.affilemanager.app.transfer.NearbySourcePreparer
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class NearbyBulkPickerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nearbyBrowserRetainsTheFullFileAndFolderSelections() = exercise(nearby = true)
    @Test fun serverBrowserKeepsItsExistingSmallerDefault() = exercise(nearby = false)

    private fun exercise(nearby: Boolean) {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val choice = mutableStateOf(0)
        val language = mutableStateOf("en")
        var submitted: List<String>? = null
        val count = if (nearby) 30_000 else RemoteCopyEngine.MAX_SELECTED_ROOTS + 1
        val files = List(count) { index -> FileEntry("/fixture/f$index.txt", "f$index.txt", EntryKind.DOCUMENT, 0, 0, false, true, true) }
        val folders = List(8_000) { index -> FileEntry("/fixture/d$index", "d$index", EntryKind.DIRECTORY, 0, 0, false, true, true) }
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale(language.value))) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config,
                LocalDensity provides Density(density.density, 1.5f),
                LocalLayoutDirection provides if (language.value == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme { key(choice.value) {
                    val entries = if (choice.value == 0) files else folders
                    LocalUploadDialog(initialDirectoryPath = "/fixture/${choice.value}", remotePath = "", initialEntries = entries,
                        initiallySelected = entries.mapTo(linkedSetOf(), FileEntry::absolutePath),
                        loadDirectory = { Result.success(entries) }, onDismiss = {}, onCopy = { submitted = it },
                        title = if (nearby) "Pasirinkti siunčiamus failus ir aplankus" else "Kopijuoti į serverį",
                        confirmLabel = "Paruošti", selectionLimit = if (nearby) NearbySourcePreparer.MAX_FILES else RemoteCopyEngine.MAX_SELECTED_ROOTS)
                } }
            }
        }
        for (scenario in if (nearby) listOf(0, 1) else listOf(0)) {
            compose.runOnIdle { choice.value = scenario; submitted = null }
            val expected = if (!nearby) RemoteCopyEngine.MAX_SELECTED_ROOTS else if (scenario == 0) 30_000 else 8_000
            for (locale in listOf("en", "de", "ar")) {
                compose.runOnIdle { language.value = locale }
                compose.waitForIdle()
                val button = compose.onNodeWithTag("local_upload_confirm").assertIsDisplayed().assertIsEnabled()
                val text = button.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text }
                assertTrue(text, text.contains("($expected)"))
                val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try { File(uiAuditDirectory(app), "bulk-picker-$nearby-$scenario-$locale.png").outputStream().use {
                    check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                } } finally { image.recycle() }
                button.performTouchInput { click() }
                compose.runOnIdle { assertEquals(expected, requireNotNull(submitted).distinct().size) }
            }
        }
    }
}
