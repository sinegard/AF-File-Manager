package com.affilemanager.app.transfer

import org.junit.Assert.*
import org.junit.Test

class NearbyDocumentPathRulesTest {
    @Test fun aFirstLevelDirectoryReturnsToTheGrantedVolumeNotTheStorageList() {
        assertEquals("1234-ABCD:", NearbyDocumentPathRules.parentDocumentId("1234-ABCD:Images", "1234-ABCD:"))
        assertEquals("1234-ABCD:Images", NearbyDocumentPathRules.parentDocumentId("1234-ABCD:Images/Trip", "1234-ABCD:"))
        assertEquals("1234-ABCD:Images", NearbyDocumentPathRules.parentDocumentId("1234-ABCD:Images/Trip", "1234-ABCD:Images"))
        assertNull(NearbyDocumentPathRules.parentDocumentId("1234-ABCD:", "1234-ABCD:"))
        assertNull(NearbyDocumentPathRules.parentDocumentId("1234-ABCD:Images", "1234-ABCD:Images"))
    }

    @Test fun parentNavigationNeverLeavesTheAlreadyGrantedSubtree() {
        for (document in listOf("1234-ABCD:Other/Child", "1234-ABCD:Images-not/Child",
            "OTHER:Images/Child", "1234-ABCD:Images/../Other", "1234-ABCD:Images/a\n")) {
            assertNull(document, NearbyDocumentPathRules.parentDocumentId(document, "1234-ABCD:Images"))
        }
    }

    @Test fun onlyTheAlreadyGrantedRemovableTreeIsMapped() {
        assertEquals("1234-ABCD:Pictures/Trip/a.jpg", NearbyDocumentPathRules.documentId(
            "/storage/1234-ABCD/Pictures/Trip/a.jpg", "1234-ABCD:Pictures"))
        assertEquals("1234-ABCD:", NearbyDocumentPathRules.documentId("/storage/1234-ABCD", "1234-ABCD:"))
        for (path in listOf("/storage/1234-ABCD/Pictures-not/a.jpg", "/storage/1234-ABCD/Other/a.jpg",
            "/storage/1234-ABCD/Pictures/../secret", "/storage/1234-ABCD/Pictures/./a", "/storage/1234-ABCD-not/Pictures/a")) {
            assertNull(path, NearbyDocumentPathRules.documentId(path, "1234-ABCD:Pictures"))
        }
        assertNull(NearbyDocumentPathRules.documentId("/storage/emulated/0/Android/data/a", "primary:"))
        assertNull(NearbyDocumentPathRules.documentId("/storage/1234-ABCD/Pictures/a\n", "1234-ABCD:"))
    }
}
