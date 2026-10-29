package com.affilemanager.app.transfer

import com.affilemanager.app.model.EntryKind
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NearbyReceiveFolderRulesTest {
    @Test fun sixStableTypeNamesStayImmediatelyInsideTheSelectedFolder() {
        val parent = File("receiving").absoluteFile
        val folders = NearbyReceiveFolderRules.destinations(parent.path)
        assertEquals(NearbyReceiveConfiguration.TYPES, folders.keys)
        assertEquals(6, folders.values.map { it.name }.distinct().size)
        assertTrue(folders.values.all { it.parentFile == parent })
        assertEquals("Images", folders.getValue(EntryKind.IMAGE).name)
        assertEquals("Apps", folders.getValue(EntryKind.APK).name)
    }
    @Test fun invalidPathTextIsRejectedBeforeAnyFilesystemAction() {
        for (input in listOf("", "folder\nother", "x".repeat(4097))) {
            assertTrue(runCatching { NearbyReceiveFolderRules.destinations(input) }.isFailure)
        }
    }
}
