package com.affilemanager.app.security

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.ui.localization.UiTranslationCatalog
import com.affilemanager.app.ui.localization.UiTranslator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class StreamingVaultAndroidTest {
    private val app get() = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()

    @Test fun theFrozenOldAndroidVaultStillOpensAndIsNeverRewritten() = runBlocking {
        val root = ownedRoot()
        try {
            val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("affmv1-legacy-vector.b64")
                .bufferedReader().use { android.util.Base64.decode(it.readText().trim(), android.util.Base64.DEFAULT) }
            val vault = File(root, "legacy.afvault").apply { writeBytes(bytes) }
            val before = sha(vault)
            assertEquals(VaultHeader("legacy.txt", 55, 1), app.graph.fileVault.inspect(vault))
            val output = app.graph.fileVault.decrypt(vault, root, "public-compatibility-vector".toCharArray())
            assertEquals("AF legacy compatibility ąčęėįšųūž\nsecond line\n", output.readText())
            assertEquals(before, sha(vault))
        } finally { check(root.deleteRecursively()) }
    }

    @Test fun actualAndroidProviderRejectsWrongKeysCorruptionAndTruncationWithoutPlaintext() = runBlocking {
        val root = ownedRoot()
        try {
            val source = File(root, "original.bin").apply { outputStream().use { output -> repeat(40) { output.write(ByteArray(65_536) { it.toByte() }) } } }
            val original = sha(source)
            val vault = File(root, "original.afvault")
            app.graph.fileVault.encrypt(source, vault, "android-test-password".toCharArray())
            assertEquals(2, app.graph.fileVault.inspect(vault).formatVersion)
            val vaultHash = sha(vault)
            val output = File(root, "restore").apply { check(mkdir()) }
            assertTrue(runCatching { app.graph.fileVault.decrypt(vault, output, "wrong-password".toCharArray()) }.isFailure)
            assertTrue(output.list()!!.isEmpty())
            val corrupt = File(root, "corrupt.afvault")
            vault.copyTo(corrupt)
            java.io.RandomAccessFile(corrupt, "rw").use { file -> file.seek(file.length() - 1); file.write(0) }
            // Always alter the final byte, regardless of its randomly generated original value.
            java.io.RandomAccessFile(vault, "r").use { file -> file.seek(file.length() - 1)
                val last = file.read(); java.io.RandomAccessFile(corrupt, "rw").use { changed -> changed.seek(changed.length() - 1); changed.write(last xor 1) } }
            assertTrue(runCatching { app.graph.fileVault.decrypt(corrupt, output, "android-test-password".toCharArray()) }.isFailure)
            assertTrue(output.list()!!.isEmpty())
            val truncated = File(root, "truncated.afvault")
            vault.copyTo(truncated)
            java.io.RandomAccessFile(truncated, "rw").use { it.setLength(it.length() - 1) }
            assertTrue(runCatching { app.graph.fileVault.decrypt(truncated, output, "android-test-password".toCharArray()) }.isFailure)
            assertTrue(output.list()!!.isEmpty())
            val restored = app.graph.fileVault.decrypt(vault, output, "android-test-password".toCharArray())
            assertEquals(original, sha(source)); assertEquals(original, sha(restored)); assertEquals(vaultHash, sha(vault))
            assertFalse(root.walkTopDown().any { it.name.startsWith(".af-vault-") })
        } finally { check(root.deleteRecursively()) }
    }

    @Test fun aFileLargerThanTheAndroidHeapStreamsWithTwentyFivePercentHeadroom() = runBlocking {
        val root = ownedRoot()
        val initial = VaultHeapSnapshot.current()
        val size = initial.maximum + 16L * 1024 * 1024
        assumeTrue("Owned fixture requires a heap below 1 GiB", size <= 1024L * 1024 * 1024)
        assumeTrue("Not enough disposable guest storage for a three-copy integrity fixture", root.usableSpace > size * 3 + 64L * 1024 * 1024)
        var accepted = false
        val peak = AtomicLong(initial.allocated - initial.freeInAllocated)
        val sampler = Executors.newSingleThreadScheduledExecutor()
        try {
            val source = File(root, "larger-than-heap.bin")
            source.outputStream().buffered().use { output ->
                val buffer = ByteArray(65_536) { (it * 17 xor (it ushr 8)).toByte() }
                var remaining = size
                while (remaining > 0) { val count = minOf(buffer.size.toLong(), remaining).toInt(); output.write(buffer, 0, count); remaining -= count }
            }
            val before = sha(source)
            sampler.scheduleAtFixedRate({
                val heap = VaultHeapSnapshot.current()
                peak.updateAndGet { maxOf(it, heap.allocated - heap.freeInAllocated) }
            }, 0, 10, TimeUnit.MILLISECONDS)
            val vault = File(root, "large.afvault")
            val start = android.os.SystemClock.elapsedRealtime()
            app.graph.fileVault.encrypt(source, vault, "android-large-file-test".toCharArray())
            val output = app.graph.fileVault.decrypt(vault, File(root, "restored"), "android-large-file-test".toCharArray())
            assertEquals(before, sha(source)); assertEquals(before, sha(output))
            val record = JSONObject().put("api", Build.VERSION.SDK_INT).put("plaintextBytes", size)
                .put("maximumHeapBytes", initial.maximum).put("initialUsedHeapBytes", initial.allocated - initial.freeInAllocated)
                .put("peakSampledHeapBytes", peak.get()).put("sampleIntervalMillis", 10)
                .put("durationMillis", android.os.SystemClock.elapsedRealtime() - start).put("sourceSha256", before)
                .put("outputSha256", sha(output)).put("vaultSha256", sha(vault))
            val result = File(app.getExternalFilesDir("validation"), "streaming-vault-memory-api${Build.VERSION.SDK_INT}.json")
            result.writeText(record.toString(2))
            assertTrue("Measured heap crossed the 25% reserve: $record", peak.get() <= initial.maximum - initial.maximum / 4)
            assertFalse(root.walkTopDown().any { it.name.startsWith(".af-vault-") })
            accepted = true
        } finally {
            sampler.shutdownNow(); check(sampler.awaitTermination(5, TimeUnit.SECONDS))
            if (accepted) check(root.deleteRecursively()) else android.util.Log.e("AFVaultAudit", "Retained failed synthetic fixture: ${root.path}")
        }
    }

    @Test fun newFailureMessagesAreAvailableInEveryShippedLanguage() {
        UiTranslationCatalog.initialize(app)
        for (language in AppLanguageManager.SUPPORTED_LANGUAGE_TAGS) for (message in listOf(
            "Failo iššifruoti nepavyko. Patikrinkite slaptafrazę arba bandykite kitą kopiją.",
            "Šiai senai šifruotai kopijai atverti nepakanka atminties. Originalus failas nepakeistas.",
            "Šiam veiksmui nepakanka laisvos atminties.",
        )) {
            val translated = UiTranslator.translate(message, language)
            assertTrue(translated.isNotBlank())
            if (language != "lt") assertNotEquals("Lithuanian fallback: $language", message, translated)
            if (language !in listOf("lt", "en")) assertNotEquals("English fallback: $language", UiTranslator.translate(message, "en"), translated)
        }
    }

    private fun ownedRoot(): File {
        check(Build.MODEL.contains("sdk", true) && app.packageName.endsWith(".debug"))
        return File(app.cacheDir, "vault-audit-${UUID.randomUUID()}").apply { check(mkdir()) }
    }
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().buffered().use { input -> val buffer = ByteArray(65_536); while (true) {
            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
        } }; digest.digest().joinToString("") { "%02x".format(it) }
    }
}
