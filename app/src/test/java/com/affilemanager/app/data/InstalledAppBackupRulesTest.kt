package com.affilemanager.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppBackupRulesTest {
    @Test
    fun backupNamesAreReadableSafeAndUseTheRightContainer() {
        val single = InstalledAppBackupRules.fileName(
            label = "Notes: personal?",
            packageName = "example.notes",
            versionName = "2.4",
            split = false,
        )
        val split = InstalledAppBackupRules.fileName(
            label = "Notes: personal?",
            packageName = "example.notes",
            versionName = "2.4",
            split = true,
        )

        assertEquals("Notes_ personal_-2.4.apk", single)
        assertEquals("Notes_ personal_-2.4.apks", split)
        assertFalse(single.any { it in "\\/:*?\"<>|" })
    }

    @Test
    fun splitArchiveKeepsBaseAndApkExtensions() {
        assertEquals("base.apk", InstalledAppBackupRules.zipEntryName(File("base.apk"), 0))
        assertEquals("split_config.arm64_v8a.apk", InstalledAppBackupRules.zipEntryName(File("split_config.arm64_v8a.apk"), 1))
        assertTrue(InstalledAppBackupRules.zipEntryName(File("config.en"), 2).endsWith(".apk"))
    }
}
