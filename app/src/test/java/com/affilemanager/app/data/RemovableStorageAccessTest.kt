package com.affilemanager.app.data

import org.junit.Assert.*
import org.junit.Test

class RemovableStorageAccessTest {
    @Test fun onlyWritableMountedLegacyRemovableVolumesNeedProviderAccess() {
        assertTrue(RemovableStorageAccess.needsDocuments(26,false,true,true,false))
        assertTrue(RemovableStorageAccess.needsDocuments(29,false,true,true,false))
        assertFalse(RemovableStorageAccess.needsDocuments(30,false,true,true,false))
        assertFalse(RemovableStorageAccess.needsDocuments(26,true,true,true,false))
        assertFalse(RemovableStorageAccess.needsDocuments(26,false,false,true,false))
        assertFalse(RemovableStorageAccess.needsDocuments(26,false,true,false,false))
        assertFalse(RemovableStorageAccess.needsDocuments(26,false,true,true,true))
    }

    @Test fun fullRootAndNarrowerFolderGrantsCannotBeConfused() {
        val root=checkNotNull(RemovableStorageAccess.initialTree("075E-0A20"))
        assertTrue(RemovableStorageAccess.belongsToVolume(root,"075e-0a20",true))
        val folder=root+"Documents"
        assertTrue(RemovableStorageAccess.belongsToVolume(folder,"075E-0A20",false))
        assertFalse(RemovableStorageAccess.belongsToVolume(folder,"075E-0A20",true))
        assertTrue(RemovableStorageAccess.belongsToVolume(root+"Documents%2FSub","075E-0A20",false))
        assertFalse(RemovableStorageAccess.belongsToVolume(root+"Documents%2FSub","075E-0A20",true))
        assertFalse(RemovableStorageAccess.belongsToVolume(root,"AAAA-AAAA",true))
        assertNull(RemovableStorageAccess.initialTree("primary"))
        assertNull(RemovableStorageAccess.initialTree("../other"))
    }

    @Test fun unselectedProvidersAndMalformedTreesNeverBecomeVolumeGrants() {
        val base=checkNotNull(RemovableStorageAccess.initialTree("075E-0A20"))
        for(uri in listOf(base.replace("content:","file:"),base.replace("externalstorage.documents","unselected.documents"),
            base+"?query=1",base+"#fragment",base+"/document/075E-0A20%3A",base+"..",base+"%2F..",base+"%00")) {
            assertFalse(uri,RemovableStorageAccess.belongsToVolume(uri,"075E-0A20",false))
        }
    }
}
