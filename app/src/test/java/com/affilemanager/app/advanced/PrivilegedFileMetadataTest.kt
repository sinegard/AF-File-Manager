package com.affilemanager.app.advanced

import org.junit.Assert.*
import org.junit.Test

class PrivilegedFileMetadataTest {
    private val sample = PrivilegedFileMetadata(2, 12, 10_001, 20_001, 0x81a0, "753a6f626a6563745f723a6170705f646174615f66696c653a733000")

    @Test fun roundTripPreservesExactSecurityAndInode() {
        assertEquals(sample, PrivilegedFileMetadata.decode(sample.encode()))
        assertTrue(sample.sameSecurity(sample.copy(device = 3, inode = 14)))
        assertFalse(sample.sameEntry(sample.copy(inode = 14)))
        for (changed in listOf(sample.copy(uid = 0), sample.copy(gid = 0), sample.copy(mode = 0x81b6), sample.copy(selinuxHex = "00"))) {
            assertFalse(sample.sameSecurity(changed))
        }
    }

    @Test fun unsupportedLabelIsDistinctFromAnEmptyOrMalformedLabel() {
        assertEquals(sample.copy(selinuxHex = null), PrivilegedFileMetadata.decode(sample.copy(selinuxHex = null).encode()))
        for (bad in listOf("", "0", "xx", "AA", "00".repeat(4_097), "6e6f742d612d6c6162656c00", "00")) {
            assertThrows(IllegalArgumentException::class.java) { PrivilegedFileMetadata.decode(sample.copy(selinuxHex = bad).encode()) }
        }
    }

    @Test fun oldOversizedAndSpecialFilePayloadsAreRejected() {
        val fields = sample.encode()
        assertThrows(IllegalArgumentException::class.java) { PrivilegedFileMetadata.decode(fields + "extra") }
        assertThrows(IllegalArgumentException::class.java) { PrivilegedFileMetadata.decode(fields.copyOf().also { it[0] = "0" }) }
        assertThrows(IllegalArgumentException::class.java) { PrivilegedFileMetadata.decode(sample.copy(uid = -1).encode()) }
        assertThrows(IllegalArgumentException::class.java) { PrivilegedFileMetadata.decode(sample.copy(mode = 0xa1ff).encode()) }
    }

    @Test fun theWireLimitCountsUtf16AndUnknownSecurityAttributesCannotBeDiscarded() {
        val context = "u:object_r:" + "a".repeat(4_065) + ":s0\u0000"
        val hex = context.toByteArray().joinToString("") { "%02x".format(it) }
        assertTrue(context.length <= PrivilegedFileMetadata.MAX_LABEL_BYTES)
        assertThrows(IllegalArgumentException::class.java) { PrivilegedFileMetadata.decode(sample.copy(selinuxHex=hex).encode()) }
        PrivilegedMetadataOperations.requireNoExtraSecurityAttribute(null)
        PrivilegedMetadataOperations.requireNoExtraSecurityAttribute(ByteArray(0))
        assertThrows(IllegalArgumentException::class.java) { PrivilegedMetadataOperations.requireNoExtraSecurityAttribute(byteArrayOf(1)) }
    }

    @Test fun policyOutputAcceptsTheObservedOldAndNewAndroidInformationHeaders() {
        val path = "/data/local/tmp/document.txt"
        val line = "SELinux: Relabeling $path from u:object_r:app_data_file:s0 to u:object_r:shell_data_file:s0."
        val expected = "u:object_r:app_data_file:s0" to "u:object_r:shell_data_file:s0"
        assertEquals(expected, PrivilegedMetadataOperations.parsePolicyLabelChange("SELinux: Loaded file_contexts\n$line\n", path))
        val header = "SELinux: Loaded file context from:\n\t\t/system/etc/selinux/plat_file_contexts\n" +
            "\t\t/system_ext/etc/selinux/system_ext_file_contexts\n\t\t/product/etc/selinux/product_file_contexts\n" +
            "\t\t/vendor/etc/selinux/vendor_file_contexts\n\t\t/odm/etc/selinux/odm_file_contexts\n"
        assertNull(PrivilegedMetadataOperations.parsePolicyLabelChange(header, path))
        assertNull(PrivilegedMetadataOperations.parsePolicyLabelChange("", path))
        assertEquals(expected, PrivilegedMetadataOperations.parsePolicyLabelChange(header + line, path))
    }

    @Test fun policyOutputCannotHideErrorsOtherTargetsOrMultipleChanges() {
        val path = "/data/local/tmp/document.txt"
        val line = "SELinux: Relabeling $path from u:object_r:app_data_file:s0 to u:object_r:shell_data_file:s0."
        for (output in listOf(
            "SELinux: Loaded file context from:\n",
            "SELinux: Loaded file context from:\n\t permission denied\n",
            "$line\n$line", line.replace(path, "$path-other"), "restorecon: permission denied",
        )) assertThrows(IllegalStateException::class.java) {
            PrivilegedMetadataOperations.parsePolicyLabelChange(output, path)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegedMetadataOperations.parsePolicyLabelChange("x".repeat(16_385), path)
        }
    }

    @Test fun actualMountTypeNotModeOrGuessedDepthIdentifiesTheStorageView() {
        val path = "/storage/emulated/0/Android/data/example/files/document.txt"
        val mount = "3277 115 0:43 / /storage/emulated rw,nosuid,nodev,noexec - fuse /dev/fuse rw"
        assertEquals("/storage/emulated", PrivilegedMetadataOperations.platformStorageMount(mount, path))
        assertNull(PrivilegedMetadataOperations.platformStorageMount(mount.replace(" - fuse ", " - ext4 "), path))
        assertNull(PrivilegedMetadataOperations.platformStorageMount(mount, path.replace("/emulated/", "/emulated-other/")))
        assertEquals("/storage/My Drive", PrivilegedMetadataOperations.platformStorageMount(
            "11 1 0:9 / /storage/My\\040Drive rw - sdcardfs none rw", "/storage/My Drive/document.txt"))
        assertNull(PrivilegedMetadataOperations.platformStorageMount(
            "11 1 0:9 / /storage/My\\012Drive rw - fuse none rw", "/storage/My\nDrive/document.txt"))
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegedMetadataOperations.platformStorageMount("x".repeat(16_385), path)
        }
    }
}
