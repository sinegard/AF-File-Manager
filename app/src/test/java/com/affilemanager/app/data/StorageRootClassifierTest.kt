package com.affilemanager.app.data

import android.os.Environment
import com.affilemanager.app.model.StorageRootKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageRootClassifierTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun onlyMountedVolumesWithAnExistingDirectoryAreVisible() {
        assertEquals(true, StorageVolumeMountPolicy.isVisible(Environment.MEDIA_MOUNTED, directoryExists = true))
        assertEquals(true, StorageVolumeMountPolicy.isVisible(Environment.MEDIA_MOUNTED_READ_ONLY, directoryExists = true))
        assertEquals(false, StorageVolumeMountPolicy.isVisible(Environment.MEDIA_UNMOUNTED, directoryExists = true))
        assertEquals(false, StorageVolumeMountPolicy.isVisible(Environment.MEDIA_MOUNTED, directoryExists = false))
    }

    @Test
    fun androidEightFallbackFindsTheStorageRootFromTheAppDirectory() {
        val root = temporary.newFolder("0123-4567")
        val appFiles = File(root, "Android/data/com.affilemanager.app/files").apply { mkdirs() }

        assertEquals(root.canonicalPath, LegacyStorageRootPolicy.fromAppExternalFilesDirectory(appFiles)?.canonicalPath)
        assertEquals(null, LegacyStorageRootPolicy.fromAppExternalFilesDirectory(File(root, "unrelated")))
    }

    @Test
    fun androidEightFallbackMatchesRemovableVolumesByUuidBeforeArrayPosition() {
        val primary = temporary.newFolder("primary")
        val sd = temporary.newFolder("AAAA-BBBB")
        val usb = temporary.newFolder("CCCC-DDDD")
        val candidates = listOf(primary, usb, sd)

        assertEquals(sd, LegacyStorageRootPolicy.chooseForVolume(false, "aaaa-bbbb", 1, primary, candidates))
        assertEquals(primary, LegacyStorageRootPolicy.chooseForVolume(true, null, 2, primary, candidates))
    }

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
