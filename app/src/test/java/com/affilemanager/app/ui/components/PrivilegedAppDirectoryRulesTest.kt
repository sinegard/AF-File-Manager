package com.affilemanager.app.ui.components

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivilegedAppDirectoryRulesTest {
    @Test
    fun appPackageFoldersAreRecognizedOnlyAtAndroidDataAndObbRoots() {
        val appFolder = entry("com.example.player", EntryKind.DIRECTORY)

        assertEquals(
            "com.example.player",
            PrivilegedAppDirectoryRules.packageName("/storage/emulated/0/Android/data", appFolder),
        )
        assertEquals(
            "com.example.player",
            PrivilegedAppDirectoryRules.packageName("/storage/emulated/0/Android/obb/", appFolder),
        )
        assertNull(PrivilegedAppDirectoryRules.packageName("/storage/emulated/0/Download", appFolder))
        assertNull(PrivilegedAppDirectoryRules.packageName("/storage/emulated/0/Android/data/com.owner", appFolder))
    }

    @Test
    fun ordinaryFoldersAndFilesKeepTheirNormalVisual() {
        assertNull(
            PrivilegedAppDirectoryRules.packageName(
                "/storage/emulated/0/Android/data",
                entry("Pictures", EntryKind.DIRECTORY),
            ),
        )
        assertNull(
            PrivilegedAppDirectoryRules.packageName(
                "/storage/emulated/0/Android/data",
                entry("com.example.player", EntryKind.OTHER),
            ),
        )
    }

    private fun entry(name: String, kind: EntryKind) = FileEntry(
        absolutePath = "/storage/emulated/0/Android/data/$name",
        name = name,
        kind = kind,
        sizeBytes = 0,
        modifiedAtMillis = 0,
        isHidden = false,
        isReadable = true,
        isWritable = true,
    )
}
