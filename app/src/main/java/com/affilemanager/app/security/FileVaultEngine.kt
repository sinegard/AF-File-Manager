package com.affilemanager.app.security

import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VaultHeader(val originalName: String, val originalSize: Long, val formatVersion: Int = 1)

/** AFFMV2 writes authenticated segments; AFFMV1 remains a read-only legacy format. */
class FileVaultEngine(private val onMutation: suspend (List<File>) -> Unit = {}) {
    companion object {
        private val MAGIC_V1 = "AFFMV1".toByteArray(Charsets.US_ASCII)
        private val MAGIC_V2 = "AFFMV2".toByteArray(Charsets.US_ASCII)
        private const val STREAMING_SUITE = 1 // Tink AES256_GCM_HKDF_1MB
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val KEY_BITS = 256
        private const val PBKDF2_ITERATIONS = 210_000
        private const val BUFFER_SIZE = 256 * 1024
        private const val MAX_NAME_BYTES = 1024
        private const val MAX_PLAINTEXT_BYTES = 64L * 1024 * 1024 * 1024
        // Includes all 1 MiB segment tags and both bounded headers at the plaintext ceiling.
        private const val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + 2L * 1024 * 1024
        private const val DECRYPT_ERROR = "Failo iššifruoti nepavyko. Patikrinkite slaptafrazę arba bandykite kitą kopiją."
    }

    private data class Envelope(
        val header: VaultHeader,
        val iterations: Int,
        val salt: ByteArray,
        val legacyIv: ByteArray?,
        val authenticatedHeader: ByteArray,
    )

    suspend fun encrypt(
        source: File,
        destination: File,
        passphrase: CharArray,
        operation: OperationContext? = null,
    ) = withContext(Dispatchers.IO) {
        var passwordKey: ByteArray? = null
        var partial: File? = null
        try {
            val inputFile = FileSystemRules.nonSymbolicSource(source)
            require(inputFile.isFile) { "Šaltinio failas nepasiekiamas" }
            val sourceSize = inputFile.length()
            val sourceModified = inputFile.lastModified()
            require(sourceSize <= MAX_PLAINTEXT_BYTES) { "Failas viršija saugyklos ribą" }
            require(passphrase.size >= 8) { "Slaptafrazę turi sudaryti bent 8 ženklai" }
            require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Toks pavadinimas jau naudojamas" }
            val name = validatedName(inputFile.name)
            require(VaultWorkingSetRules.permitsStreaming(VaultHeapSnapshot.current())) {
                "Šiam veiksmui nepakanka laisvos atminties."
            }
            val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
            val header = encodeV2Header(name, sourceSize, salt)
            passwordKey = deriveKey(passphrase, salt, PBKDF2_ITERATIONS)
            val primitive = StreamingVaultCrypto.primitive(passwordKey)
            checkpoint(operation)
            val staged = File.createTempFile(".af-vault-", ".partial", requireNotNull(destination.absoluteFile.parentFile))
            partial = staged
            FileOutputStream(staged).use { raw ->
                BufferedOutputStream(raw).use { output ->
                    output.write(header)
                    // Closing the crypto stream finalizes its last segment, not the file descriptor.
                    primitive.newEncryptingStream(NonClosingOutput(output), header).use { encrypted ->
                        BufferedInputStream(inputFile.inputStream()).use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var copied = 0L
                            try {
                                while (true) {
                                    checkpoint(operation)
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    copied = Math.addExact(copied, read.toLong())
                                    require(copied <= sourceSize) { "Failas pasikeitė arba viršijo leistiną dydį" }
                                    encrypted.write(buffer, 0, read)
                                    operation?.progress(byteDelta = read.toLong(), currentName = inputFile.name)
                                }
                                require(copied == sourceSize && !Files.isSymbolicLink(source.toPath()) &&
                                    inputFile.length() == sourceSize && inputFile.lastModified() == sourceModified) {
                                    "Failas pasikeitė arba viršijo leistiną dydį"
                                }
                            } finally { buffer.fill(0) }
                        }
                    }
                    output.flush()
                    raw.fd.sync()
                }
            }
            checkpoint(operation)
            require(staged.isFile && staged.length() > header.size) { "Šifruotas failas nesukurtas" }
            Files.move(staged.toPath(), destination.toPath())
            onMutation(listOf(destination))
            operation?.progress(itemDelta = 1, currentName = inputFile.name)
        } finally {
            passwordKey?.fill(0)
            passphrase.fill('\u0000')
            partial?.takeIf(File::exists)?.delete()
        }
    }

    suspend fun decrypt(
        source: File,
        destinationDirectory: File,
        passphrase: CharArray,
        operation: OperationContext? = null,
    ): File = withContext(Dispatchers.IO) {
        var passwordKey: ByteArray? = null
        var partial: File? = null
        try {
            val inputFile = FileSystemRules.nonSymbolicSource(source)
            require(inputFile.isFile && inputFile.length() <= MAX_ENCRYPTED_BYTES) {
                "Saugyklos failas nepasiekiamas arba per didelis"
            }
            val sourceSize = inputFile.length()
            val sourceModified = inputFile.lastModified()
            DataInputStream(BufferedInputStream(inputFile.inputStream())).use { input ->
                val envelope = readEnvelope(input)
                val header = envelope.header
                val heap = VaultHeapSnapshot.current()
                if (header.formatVersion == 1) {
                    require(VaultWorkingSetRules.permitsLegacy(sourceSize - envelope.authenticatedHeader.size, header.originalSize, heap)) {
                        "Šiai senai šifruotai kopijai atverti nepakanka atminties. Originalus failas nepakeistas."
                    }
                } else {
                    require(VaultWorkingSetRules.permitsStreaming(heap)) { "Šiam veiksmui nepakanka laisvos atminties." }
                }
                require(destinationDirectory.isDirectory || destinationDirectory.mkdirs()) { "Paskirties aplankas nepasiekiamas" }
                val destination = FileSystemRules.keepBothTarget(File(destinationDirectory, header.originalName))
                val staged = File.createTempFile(".af-vault-", ".partial", destinationDirectory)
                partial = staged
                passwordKey = deriveKey(passphrase, envelope.salt, envelope.iterations)
                checkpoint(operation)
                FileOutputStream(staged).use { raw ->
                    BufferedOutputStream(raw).use { output ->
                        val written = if (header.formatVersion == 1) {
                            decryptLegacy(input, output, requireNotNull(passwordKey), envelope, source.name, operation)
                        } else {
                            decryptStreaming(input, output, requireNotNull(passwordKey), envelope, source.name, operation)
                        }
                        require(written == header.originalSize) { "Iššifruoto failo dydis nesutampa" }
                        output.flush()
                        raw.fd.sync()
                    }
                }
                checkpoint(operation)
                require(!Files.isSymbolicLink(source.toPath()) && inputFile.length() == sourceSize && inputFile.lastModified() == sourceModified) {
                    "Failas pasikeitė arba viršijo leistiną dydį"
                }
                Files.move(staged.toPath(), destination.toPath())
                onMutation(listOf(destination))
                operation?.progress(itemDelta = 1, currentName = destination.name)
                destination
            }
        } finally {
            passwordKey?.fill(0)
            passphrase.fill('\u0000')
            partial?.takeIf(File::exists)?.delete()
        }
    }

    suspend fun inspect(source: File): VaultHeader = withContext(Dispatchers.IO) {
        val inputFile = FileSystemRules.nonSymbolicSource(source)
        require(inputFile.isFile && inputFile.length() <= MAX_ENCRYPTED_BYTES) { "Saugyklos failas nepasiekiamas arba per didelis" }
        DataInputStream(BufferedInputStream(inputFile.inputStream())).use { readEnvelope(it).header }
    }

    private suspend fun decryptStreaming(
        input: DataInputStream, output: OutputStream, key: ByteArray, envelope: Envelope,
        name: String, operation: OperationContext?,
    ): Long {
        val primitive = StreamingVaultCrypto.primitive(key)
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        try {
            primitive.newDecryptingStream(input, envelope.authenticatedHeader).use { decrypted ->
                while (true) {
                    checkpoint(operation)
                    val read = try { decrypted.read(buffer) } catch (error: IOException) {
                        throw IOException(DECRYPT_ERROR, error)
                    }
                    if (read < 0) break // EOF is accepted only after Tink authenticates the final segment.
                    written = Math.addExact(written, read.toLong())
                    require(written <= envelope.header.originalSize) { "Iššifruotas turinys viršijo deklaruotą dydį" }
                    output.write(buffer, 0, read)
                    operation?.progress(byteDelta = read.toLong(), currentName = name)
                }
            }
        } finally { buffer.fill(0) }
        return written
    }

    private suspend fun decryptLegacy(
        input: DataInputStream, output: OutputStream, key: ByteArray, envelope: Envelope,
        name: String, operation: OperationContext?,
    ): Long {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, requireNotNull(envelope.legacyIv)))
        val buffer = ByteArray(BUFFER_SIZE)
        var written = 0L
        fun writePlaintext(bytes: ByteArray?) {
            if (bytes == null) return
            try {
                written = Math.addExact(written, bytes.size.toLong())
                require(written <= envelope.header.originalSize) { "Iššifruotas turinys viršijo deklaruotą dydį" }
                output.write(bytes)
            } finally { bytes.fill(0) }
        }
        try {
            while (true) {
                checkpoint(operation)
                val read = input.read(buffer)
                if (read < 0) break
                writePlaintext(cipher.update(buffer, 0, read))
                operation?.progress(byteDelta = read.toLong(), currentName = name)
            }
            try { writePlaintext(cipher.doFinal()) } catch (error: GeneralSecurityException) {
                throw IOException(DECRYPT_ERROR, error)
            }
        } finally { buffer.fill(0) }
        return written
    }

    private fun readEnvelope(input: DataInputStream): Envelope {
        try {
            val captured = ByteArrayOutputStream(MAX_NAME_BYTES + 80)
            val copy = DataOutputStream(captured)
            fun readInt(): Int = input.readInt().also(copy::writeInt)
            fun readBytes(size: Int): ByteArray = ByteArray(size).also { input.readFully(it); copy.write(it) }
            val magic = readBytes(MAGIC_V2.size)
            val version = when {
                magic.contentEquals(MAGIC_V1) -> 1
                magic.contentEquals(MAGIC_V2) -> 2
                else -> throw IllegalArgumentException("Tai ne AF File Manager saugyklos failas")
            }
            if (version == 2) require(readInt() == STREAMING_SUITE) { "Tai ne AF File Manager saugyklos failas" }
            val iterations = readInt().also { require(it in 100_000..1_000_000) { "Netinkama rakto išvedimo riba" } }
            val saltSize = readInt().also { require(it == SALT_BYTES) { "Netinkama druska" } }
            val salt = readBytes(saltSize)
            val iv = if (version == 1) {
                val size = readInt().also { require(it == IV_BYTES) { "Netinkamas IV" } }
                readBytes(size)
            } else null
            val nameSize = readInt().also { require(it in 1..MAX_NAME_BYTES) { "Netinkamas failo vardas" } }
            val nameBytes = readBytes(nameSize)
            val name = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(nameBytes)).toString()
            } catch (error: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Netinkamas failo vardas", error)
            }
            val size = input.readLong().also(copy::writeLong)
            require(size in 0..MAX_PLAINTEXT_BYTES) { "Netinkamas pradinis dydis" }
            val safeName = if (version == 1) FileSystemRules.validateFileName(name).getOrThrow() else validatedName(name)
            return Envelope(VaultHeader(safeName, size, version), iterations, salt, iv, captured.toByteArray())
        } catch (error: EOFException) {
            throw IOException("Netikėtai baigėsi saugyklos antraštė", error)
        }
    }

    private fun encodeV2Header(name: String, size: Long, salt: ByteArray): ByteArray =
        ByteArrayOutputStream(MAX_NAME_BYTES + 80).also { bytes ->
            DataOutputStream(bytes).use { output ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                require(nameBytes.size in 1..MAX_NAME_BYTES) { "Failo vardas per ilgas" }
                output.write(MAGIC_V2)
                output.writeInt(STREAMING_SUITE)
                output.writeInt(PBKDF2_ITERATIONS)
                output.writeInt(salt.size)
                output.write(salt)
                output.writeInt(nameBytes.size)
                output.write(nameBytes)
                output.writeLong(size)
            }
        }.toByteArray()

    private fun validatedName(name: String): String {
        val safe = FileSystemRules.validateFileName(name).getOrThrow()
        require(safe == name) { "Netinkamas failo vardas" }
        return safe
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private suspend fun checkpoint(operation: OperationContext?) {
        currentCoroutineContext().ensureActive()
        operation?.checkpoint()
    }

    private class NonClosingOutput(output: OutputStream) : FilterOutputStream(output) {
        override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
        override fun close() { flush() }
    }
}
