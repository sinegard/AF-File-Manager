package com.affilemanager.app.ui.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkContentsDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun signedContainerStyleZipBrowsesDirectoriesWithoutChangingBytes() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val file = File(app.cacheDir, "package-${UUID.randomUUID()}.apks")
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf("base.apk", "res/layout/main.xml", "META-INF/CERT.SF").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        val originalHash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        try {
            compose.setContent { MaterialTheme { ApkContentsDialog(file, onDismiss = {}) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("res").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasTestTag("apk_contents_entry") and hasAnyDescendant(hasText("res")),
                useUnmergedTree = true).performClick()
            compose.onNode(hasTestTag("apk_contents_entry") and hasAnyDescendant(hasText("layout")),
                useUnmergedTree = true).performClick()
            compose.onNode(hasTestTag("apk_contents_entry") and hasAnyDescendant(hasText("main.xml")),
                useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("apk_contents_back").performClick()
            compose.onNode(hasTestTag("apk_contents_entry") and hasAnyDescendant(hasText("layout")),
                useUnmergedTree = true).assertIsDisplayed()
            assertArrayEquals(originalHash, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { file.delete() }
    }
}
