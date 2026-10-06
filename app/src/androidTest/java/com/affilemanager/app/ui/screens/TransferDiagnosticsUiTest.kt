package com.affilemanager.app.ui.screens

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.transfer.*
import com.affilemanager.app.ui.localization.UiTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID

class TransferDiagnosticsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun optInExportAndClearWorkInEnglishLithuanianLongAndRtlLayoutsWithoutChangingTheConnection() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        val locale = mutableStateOf("en")
        val visible = mutableStateOf(true)
        var exported: File? = null
        TransferDiagnostics.initialize(app)
        val wasEnabled = TransferDiagnostics.enabled.value
        val initialPeer = NearbyTransferController.connection.pairing()
        val initialState = NearbyTransferController.state.value
        try {
            runBlocking { TransferDiagnostics.setEnabled(app, false); TransferDiagnostics.clear(app) }
            compose.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    setLocales(LocaleList(Locale(locale.value))); screenWidthDp = 360; screenHeightDp = 780
                }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalConfiguration provides configuration,
                    LocalDensity provides Density(density.density, 1.5f),
                    LocalLayoutDirection provides if (locale.value == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MaterialTheme { if (visible.value) TransferDiagnosticsDialog(
                        onDismiss = { visible.value = false }, onExport = { exported = it }) }
                }
            }
            compose.onNodeWithTag("transfer_diagnostics_enabled").assertIsOff()
            for (language in listOf("en", "lt", "de", "ar")) {
                compose.runOnIdle { locale.value = language }
                compose.onNodeWithText(UiTranslator.translate("Perdavimo diagnostika", language)).assertIsDisplayed()
                compose.onNodeWithText(UiTranslator.translate("Duomenys lieka šiame telefone.", language)).assertIsDisplayed()
                listOf("enabled", "clear", "export", "close").forEach { suffix ->
                    val node = compose.onNodeWithTag("transfer_diagnostics_$suffix").assertIsDisplayed().fetchSemanticsNode()
                    assertTrue("$language/$suffix touch target", node.size.width > 0 && node.size.height > 0)
                }
                compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                    .fetchSemanticsNodes().forEach { node ->
                        val layouts = mutableListOf<TextLayoutResult>()
                        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                        layouts.forEach { layout ->
                            assertFalse("$language truncated ${layout.layoutInput.text}", layout.multiParagraph.didExceedMaxLines)
                            assertTrue("$language clipped ${layout.layoutInput.text}", layout.multiParagraph.height <= node.size.height + 1f)
                        }
                    }
                val snapshot = File(app.cacheDir, "validation/transfer-diagnostics-$language.png")
                snapshot.parentFile!!.mkdirs()
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                    snapshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            compose.onNodeWithTag("transfer_diagnostics_enabled").performClick()
            compose.waitUntil(5_000) { TransferDiagnostics.enabled.value }
            runBlocking { TransferDiagnostics.record(TransferDiagnosticEvent(TransferRole.RECEIVE, TransferPhase.WRITE,
                UUID.randomUUID().toString(), 1, 1234, 5678, failure = TransferFailure.SPACE)) }
            compose.onNodeWithTag("transfer_diagnostics_export").performClick()
            compose.waitUntil(5_000) { exported != null }
            val report = requireNotNull(exported)
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", report)
            app.contentResolver.openInputStream(uri)!!.use { stream ->
                assertTrue(stream.bufferedReader().readText().contains("AF-XFER-SPACE"))
            }
            assertTrue(report.canonicalPath.startsWith(File(app.cacheDir, "transfer-diagnostics").canonicalPath + File.separator))
            assertTrue(report.readText().contains("AF-XFER-SPACE"))
            assertTrue(report.readText().contains("api=${android.os.Build.VERSION.SDK_INT}"))
            compose.onNodeWithTag("transfer_diagnostics_clear").performClick()
            compose.waitUntil(5_000) { !report.exists() }
            assertFalse(runBlocking { TransferDiagnostics.export(app) }.readText().contains("AF-XFER-SPACE"))
            assertEquals(initialPeer, NearbyTransferController.connection.pairing())
            assertEquals(initialState, NearbyTransferController.state.value)
            compose.onNodeWithTag("transfer_diagnostics_close").performClick()
            compose.onNodeWithTag("transfer_diagnostics_dialog").assertDoesNotExist()
            compose.runOnIdle { visible.value = true }
            compose.onNodeWithTag("transfer_diagnostics_dialog").assertIsDisplayed()
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag("transfer_diagnostics_dialog").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("transfer_diagnostics_dialog").assertDoesNotExist()
            assertEquals(initialPeer, NearbyTransferController.connection.pairing())
            assertEquals(initialState, NearbyTransferController.state.value)
        } finally {
            runBlocking { TransferDiagnostics.setEnabled(app, wasEnabled); TransferDiagnostics.clear(app) }
        }
    }
}
