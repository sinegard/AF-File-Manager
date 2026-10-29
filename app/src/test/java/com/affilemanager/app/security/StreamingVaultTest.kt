package com.affilemanager.app.security

import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.operations.OperationSnapshot
import com.affilemanager.app.operations.OperationStatus
import com.google.crypto.tink.AccessesPartialKey
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.util.SecretBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

abstract class StreamingVaultFixture {
    @get:Rule val temporary = TemporaryFolder()
    protected val engine = FileVaultEngine()

    @Before fun recordWorkingSet() { println("AF_VAULT_JVM_HEAP ${VaultHeapSnapshot.current()}") }

    protected fun operation(action: (OperationSnapshot) -> Unit) = OperationContext("test", MutableStateFlow(false)) { transform ->
        action(OperationSnapshot("test", "test", OperationStatus.RUNNING).transform())
    }
    protected fun data(size: Int) = ByteArray(size) { ((it * 31 + 17) xor (it ushr 10)).toByte() }
    protected fun digest(file: File): ByteArray = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input -> val bytes = ByteArray(64 * 1024); while (true) {
            val read = input.read(bytes); if (read < 0) break; digest.update(bytes, 0, read)
        } }; digest.digest()
    }
    protected fun assertNoStages(root: File) = assertFalse(root.walkTopDown().any { it.name.startsWith(".af-vault-") })
    protected data class Parsed(val salt: ByteArray, val iterations: Int, val length: Int, val nameOffset: Int)
    protected fun header(bytes: ByteArray): Parsed {
        val raw = ByteArrayInputStream(bytes)
        val input = DataInputStream(raw)
        assertEquals("AFFMV2", String(ByteArray(6).also(input::readFully), Charsets.US_ASCII))
        assertEquals(1, input.readInt())
        val iterations = input.readInt()
        val salt = ByteArray(input.readInt()).also(input::readFully)
        val nameLength = input.readInt()
        val nameOffset = bytes.size - raw.available()
        input.skipBytes(nameLength)
        input.readLong()
        return Parsed(salt, iterations, bytes.size - raw.available(), nameOffset)
    }
}

class StreamingVaultTest : StreamingVaultFixture() {

    @Test fun emptySmallAndSegmentBoundariesRoundTripWithTheSameNameAndSource() = runBlocking {
        for (size in listOf(0, 1, 15, 16, 1024, 1_048_519, 1_048_520, 1_048_521, 1_048_560, 2_097_104, 3_145_731)) {
            println("AF_VAULT_BOUNDARY size=$size heap=${VaultHeapSnapshot.current()}")
            val root = temporary.newFolder("case-$size")
            val source = File(root, "žodis-$size.bin").apply { writeBytes(data(size)) }
            val before = digest(source)
            val vault = File(root, "copy.afvault")
            val passphrase = "synthetic-test-password".toCharArray()
            engine.encrypt(source, vault, passphrase)
            assertTrue(passphrase.all { it == '\u0000' })
            assertEquals("AFFMV2", vault.inputStream().use { String(it.readNBytes(6), Charsets.US_ASCII) })
            assertEquals(VaultHeader(source.name, size.toLong(), 2), engine.inspect(vault))
            val restored = engine.decrypt(vault, File(root, "output"), "synthetic-test-password".toCharArray())
            assertEquals(source.name, restored.name)
            assertArrayEquals(before, digest(source))
            assertArrayEquals(before, digest(restored))
            assertNoStages(root)
        }
    }
}

class StreamingVaultBasicTest : StreamingVaultFixture() {
    @AccessesPartialKey
    @Test fun payloadCanBeReadIndependentlyUsingTheStandardTinkPrimitiveAndExactHeader() = runBlocking {
        val source = temporary.newFile("independent.bin").apply { writeBytes(data(2_400_123)) }
        val vault = File(temporary.root, "independent.afvault")
        engine.encrypt(source, vault, "synthetic-test-password".toCharArray())
        val bytes = vault.readBytes()
        val envelope = header(bytes)
        val spec = PBEKeySpec("synthetic-test-password".toCharArray(), envelope.salt, envelope.iterations, 256)
        val passwordKey = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        try {
            // Select the documented suite independently, without the production crypto helper.
            val key = AesGcmHkdfStreamingKey.create(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB,
                SecretBytes.copyFrom(passwordKey, InsecureSecretKeyAccess.get()))
            val primitive = KeysetHandle.newBuilder().addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary())
                .build().getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
            primitive.newDecryptingStream(
                ByteArrayInputStream(bytes, envelope.length, bytes.size - envelope.length),
                bytes.copyOfRange(0, envelope.length),
            ).use { assertArrayEquals(digest(source), MessageDigest.getInstance("SHA-256").digest(it.readBytes())) }
        } finally { passwordKey.fill(0) }
    }

    @Test fun wrongPasswordLeavesTheVaultOriginalAndDestinationUntouched() = runBlocking {
        val source = temporary.newFile("secret.bin").apply { writeBytes(data(1_200_000)) }
        val vault = File(temporary.root, "secret.afvault")
        engine.encrypt(source, vault, "synthetic-test-password".toCharArray())
        val before = digest(vault)
        val output = temporary.newFolder("output")
        val existing = File(output, source.name).apply { writeText("existing output") }
        val password = "wrong-password".toCharArray()
        val failure = runCatching { engine.decrypt(vault, output, password) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("Patikrinkite slaptafrazę"))
        assertTrue(password.all { it == '\u0000' })
        assertArrayEquals(before, digest(vault))
        assertEquals("existing output", existing.readText())
        assertEquals(listOf(source.name), output.list()!!.toList())
    }
}

class StreamingVaultCorruptionTest : StreamingVaultFixture() {
    @Test fun outerHeaderBodyFinalTagTruncationAppendAndReorderingCannotPublishPlaintext() = runBlocking {
        val source = temporary.newFile("message.bin").apply { writeBytes(data(3 * 1_048_576 + 123)) }
        val vault = File(temporary.root, "message.afvault")
        engine.encrypt(source, vault, "synthetic-test-password".toCharArray())
        val original = vault.readBytes()
        val parsed = header(original)
        val mutations = linkedMapOf<String, ByteArray>()
        fun flipped(index: Int) = original.copyOf().apply { this[index] = (this[index].toInt() xor 1).toByte() }
        mutations["header-name"] = flipped(parsed.nameOffset)
        mutations["header-size"] = flipped(parsed.length - 1)
        mutations["header-salt"] = flipped(18)
        mutations["body"] = flipped(parsed.length + 200)
        mutations["last-tag"] = flipped(original.lastIndex)
        for (length in listOf(0, 5, 20, parsed.length - 1, parsed.length, parsed.length + 40, original.size - 1, original.size - 16)) {
            mutations["truncated-$length"] = original.copyOf(length)
        }
        mutations["appended"] = original + byteArrayOf(1)
        mutations["reordered"] = original.copyOf().apply {
            val first = copyOfRange(parsed.length + 1_048_576, parsed.length + 2_097_152)
            copyInto(this, parsed.length + 1_048_576, parsed.length + 2_097_152, parsed.length + 3_145_728)
            first.copyInto(this, parsed.length + 2_097_152)
        }
        for ((label, bytes) in mutations) {
            val changed = File(temporary.root, "$label.afvault").apply { writeBytes(bytes) }
            val destination = temporary.newFolder(label)
            assertTrue(label, runCatching { engine.decrypt(changed, destination, "synthetic-test-password".toCharArray()) }.isFailure)
            assertTrue("Unauthenticated output/stage after $label", destination.list()!!.isEmpty())
            assertArrayEquals("Input was changed after $label", bytes, changed.readBytes())
        }
        assertArrayEquals(original, vault.readBytes())
    }
}

class StreamingVaultLifecycleTest : StreamingVaultFixture() {
    @Test fun unknownSuiteAndHostileLengthsFailBeforeAnyOutputIsCreated() = runBlocking {
        val source = temporary.newFile("small.txt").apply { writeText("private") }
        val vault = File(temporary.root, "small.afvault")
        engine.encrypt(source, vault, "synthetic-test-password".toCharArray())
        val bytes = vault.readBytes()
        for ((offset, value) in listOf(6 to 999, 10 to Int.MAX_VALUE, 14 to Int.MAX_VALUE, 34 to Int.MAX_VALUE)) {
            val changed = File(temporary.root, "bad-$offset.afvault").apply {
                writeBytes(bytes.copyOf().apply { ByteBuffer.wrap(this).putInt(offset, value) })
            }
            val output = File(temporary.root, "not-created-$offset")
            assertTrue(runCatching { engine.inspect(changed) }.isFailure)
            assertTrue(runCatching { engine.decrypt(changed, output, "synthetic-test-password".toCharArray()) }.isFailure)
            assertFalse(output.exists())
        }
    }

    @Test fun cancellationDuringEncryptionRemovesOnlyItsOwnStage() = runBlocking {
        val source = temporary.newFile("cancel.bin").apply { writeBytes(data(2_000_000)) }
        val vault = File(temporary.root, "cancel.afvault")
        val before = digest(source)
        val failure = runCatching {
            engine.encrypt(source, vault, "synthetic-test-password".toCharArray(), operation {
                if (it.completedBytes > 0) throw CancellationException("test cancellation")
            })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(vault.exists())
        assertArrayEquals(before, digest(source))
        assertNoStages(temporary.root)
    }

    @Test fun cancellationDuringDecryptionCannotLeaveUnauthenticatedOutput() = runBlocking {
        val source = temporary.newFile("cancel.bin").apply { writeBytes(data(2_000_000)) }
        val vault = File(temporary.root, "cancel.afvault")
        engine.encrypt(source, vault, "synthetic-test-password".toCharArray())
        val output = temporary.newFolder("output")
        val failure = runCatching {
            engine.decrypt(vault, output, "synthetic-test-password".toCharArray(), operation {
                if (it.completedBytes > 0) throw CancellationException("test cancellation")
            })
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(output.list()!!.isEmpty())
    }

    @Test fun aDestinationCreatedDuringEncryptionIsNotReplaced() = runBlocking {
        val source = temporary.newFile("late.bin").apply { writeBytes(data(2_000_000)) }
        val vault = File(temporary.root, "late.afvault")
        val failure = runCatching {
            engine.encrypt(source, vault, "synthetic-test-password".toCharArray(), operation {
                if (it.completedBytes > 0 && !vault.exists()) vault.writeText("created by another operation")
            })
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("created by another operation", vault.readText())
        assertNoStages(temporary.root)
    }

    @Test fun aDestinationCreatedDuringDecryptionIsNotReplaced() = runBlocking {
        val source = temporary.newFile("late.bin").apply { writeBytes(data(2_000_000)) }
        val vault = File(temporary.root, "late.afvault")
        engine.encrypt(source, vault, "synthetic-test-password".toCharArray())
        val output = temporary.newFolder("output")
        val occupied = File(output, source.name)
        val failure = runCatching {
            engine.decrypt(vault, output, "synthetic-test-password".toCharArray(), operation {
                if (it.completedBytes > 0 && !occupied.exists()) occupied.writeText("new file")
            })
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("new file", occupied.readText())
        assertEquals(listOf(source.name), output.list()!!.toList())
    }

    @Test fun independentConcurrentOperationsDoNotShareKeysOrStaging() = runBlocking {
        val source = temporary.newFile("parallel.bin").apply { writeBytes(data(1_200_000)) }
        (0..2).map { index -> async {
            val vault = File(temporary.root, "parallel-$index.afvault")
            engine.encrypt(source, vault, "password-for-$index".toCharArray())
            val output = temporary.newFolder("out-$index")
            assertArrayEquals(digest(source), digest(engine.decrypt(vault, output, "password-for-$index".toCharArray())))
        } }.awaitAll()
        assertNoStages(temporary.root)
    }

}
