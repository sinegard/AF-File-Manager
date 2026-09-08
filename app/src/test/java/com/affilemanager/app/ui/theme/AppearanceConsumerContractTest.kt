package com.affilemanager.app.ui.theme

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Prevent new screens from bypassing the app-owned appearance roles. Runtime tests cover rendering. */
class AppearanceConsumerContractTest {
    private val source = File("src/main/java/com/affilemanager/app").takeIf { it.isDirectory }
        ?: File("app/src/main/java/com/affilemanager/app")

    @Test fun appScreensUseSharedPopupCardAndSurfaceComponents() {
        val rawImport = Regex("(?m)^import androidx\\.compose\\.material3\\.(AlertDialog|DropdownMenu|Card|ElevatedCard|Surface|FilterChip|Button)\\r?$")
        val violations = source.walkTopDown().filter { it.extension == "kt" && "/ui/theme/" !in it.invariantSeparatorsPath }
            .filter { rawImport.containsMatchIn(it.readText()) }.map { it.relativeTo(source).path }.toList()
        assertEquals("Direct Material imports bypass AF's global appearance contract", emptyList<String>(), violations)
    }

    @Test fun everyPageSizedBrowserUsesTheSharedPageBackground() {
        listOf("ui/screens/FileCategoryBrowserDialog.kt", "ui/screens/AdvancedStorageBrowserDialog.kt",
            "ui/screens/SafBrowserDialog.kt", "ui/screens/TrashBrowserDialog.kt", "ui/screens/CleanupReviewDialog.kt",
            "ui/screens/AfWorkflowDialog.kt", "ui/preview/FilePreviewDialog.kt", "ui/preview/PdfSignatureDialog.kt",
            "ui/terminal/TerminalOverlay.kt", "picker/FilePickerActivity.kt", "apk/SplitApkInstallActivity.kt").forEach {
            assertTrue(it, File(source, it).readText().contains("AppearancePage("))
        }
    }
}
