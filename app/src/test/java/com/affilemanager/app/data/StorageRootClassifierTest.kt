package com.affilemanager.app.data

import com.affilemanager.app.model.StorageRootKind
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageRootClassifierTest {
    @Test
    fun primaryVolumeIsAlwaysInternal() {
        assertEquals(
            StorageRootKind.INTERNAL,
            classify(primary = true, removable = false, description = "Internal shared storage"),
        )
    }

    @Test
    fun usbAndOtgDescriptionsUseTheUsbKind() {
        assertEquals(StorageRootKind.USB_STORAGE, classify(description = "USB drive"))
        assertEquals(StorageRootKind.USB_STORAGE, classify(description = "OTG storage"))
    }

    @Test
    fun singleRemovableVolumeUsesUsbWhenMassStorageIsConnected() {
        assertEquals(
            StorageRootKind.USB_STORAGE,
            classify(description = "External storage", usbMassStorageConnected = true, removableVolumeCount = 1),
        )
    }

    @Test
    fun sdDescriptionAndUnknownRemovableVolumeStayDistinct() {
        assertEquals(StorageRootKind.SD_CARD, classify(description = "SD card"))
        assertEquals(StorageRootKind.REMOVABLE, classify(description = "External storage"))
    }

    private fun classify(
        primary: Boolean = false,
        removable: Boolean = true,
        description: String,
        usbMassStorageConnected: Boolean = false,
        removableVolumeCount: Int = 1,
    ): StorageRootKind = StorageRootClassifier.classify(
        primary = primary,
        removable = removable,
        description = description,
        path = "/storage/ABCD-1234",
        usbMassStorageConnected = usbMassStorageConnected,
        removableVolumeCount = removableVolumeCount,
    )
}
