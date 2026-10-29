package com.affilemanager.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in against the frozen pre-change APK; retains only a synthetic, non-secret vector. */
@RunWith(AndroidJUnit4::class)
class LegacyVaultCompatibilityFixtureTest {
    @Test fun captureActualLegacyWriterVector() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afCaptureLegacy") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.getExternalFilesDir(null), "validation/audit-legacy-vector").apply {
            check(!exists()); check(mkdirs())
        }
        val source = File(root, "legacy.txt").apply { writeText("AF legacy compatibility ąčęėįšųūž\nsecond line\n") }
        val vault = File(root, "legacy.afvault")
        FileVaultEngine().encrypt(source, vault, "public-compatibility-vector".toCharArray())
        assertEquals("AFFMV1", vault.inputStream().use { String(ByteArray(6).also { bytes -> check(it.read(bytes) == 6) }, Charsets.US_ASCII) })
    }
}
