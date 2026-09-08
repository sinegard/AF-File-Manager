package com.affilemanager.app.apk

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkAbiScannerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsOnlyKnownNativeLibraryDirectories() {
        val apk = temporary.newFile("sample.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            listOf("lib/arm64-v8a/liba.so", "lib/x86_64/libb.so", "lib/unknown/libc.so", "assets/arm64-v8a.so")
                .forEach { path -> zip.putNextEntry(ZipEntry(path)); zip.write(byteArrayOf(1)); zip.closeEntry() }
        }
        assertEquals(listOf("arm64-v8a", "x86_64"), ApkAbiScanner.scan(apk))
    }
}
