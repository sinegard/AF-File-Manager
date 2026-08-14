package com.affilemanager.app.security

import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VaultHeader(
    val originalName: String,
    val originalSize: Long,
)

class FileVaultEngine {
    companion object {
        private val MAGIC = "AFFMV1".toByteArray(Charsets.US_ASCII)
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val KEY_BITS = 256
        private const val PBKDF2_ITERATIONS = 210_000
        private const val GCM_TAG_BITS = 128
        private const val BUFFER_SIZE = 256 * 1_024
        private const val MAX_NAME_BYTES = 1_024
        private const val MAX_VAULT_FILE_BYTES = 64L * 1_024 * 1_024 * 1_024
    }

    suspend fun encrypt(
        source: File,
        destination: File,
        passphrase: CharArray,
        operation: OperationContext? = null,
    ) = withContext(Dispatchers.IO) {
        require(source.isFile) { "Šaltinio failas nepasiekiamas" }
        require(source.length() <= MAX_VAULT_FILE_BYTES) { "Failas viršija saugyklos ribą" }
        require(passphrase.size >= 8) { "Slaptafrazę turi sudaryti bent 8 ženklai" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(passphrase, salt)
        val nameBytes = source.name.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= MAX_NAME_BYTES) { "Failo vardas per ilgas" }
        val partial = File(destination.parentFile, ".${destination.name}.partial")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            DataOutputStream(BufferedOutputStream(partial.outputStream())).use { output ->
                output.write(MAGIC)
                output.writeInt(PBKDF2_ITERATIONS)
                output.writeInt(salt.size)
                output.write(salt)
                output.writeInt(iv.size)
                output.write(iv)
                output.writeInt(nameBytes.size)
                output.write(nameBytes)
                output.writeLong(source.length())

                BufferedInputStream(source.inputStream()).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        operation?.checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        cipher.update(buffer, 0, read)?.let(output::write)
                        operation?.progress(byteDelta = read.toLong(), currentName = source.name)
                    }
                    output.write(cipher.doFinal())
                }
            }
            require(partial.isFile && partial.length() > 0) { "Šifruotas failas nesukurtas" }
            if (destination.exists()) require(destination.delete()) { "Esamo failo pakeisti nepavyko" }
            require(partial.renameTo(destination)) { "Šifruoto failo užbaigti nepavyko" }
            operation?.progress(itemDelta = 1, currentName = source.name)
        } finally {
            key.encoded?.fill(0)
            passphrase.fill('\u0000')
            nameBytes.fill(0)
            if (partial.exists()) partial.delete()
        }
    }

    suspend fun decrypt(
        source: File,
        destinationDirectory: File,
        passphrase: CharArray,
        operation: OperationContext? = null,
    ): File = withContext(Dispatchers.IO) {
        require(source.isFile && source.length() <= MAX_VAULT_FILE_BYTES) { "Saugyklos failas nepasiekiamas arba per didelis" }
        require(destinationDirectory.isDirectory || destinationDirectory.mkdirs()) { "Paskirties aplankas nepasiekiamas" }
        var derivedKey: SecretKeySpec? = null
        var partial: File? = null
        try {
            DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC)) { "Tai ne AF File Manager saugyklos failas" }
                val iterations = input.readInt()
                require(iterations in 100_000..1_000_000) { "Netinkama rakto išvedimo riba" }
                val saltSize = input.readInt()
                require(saltSize == SALT_BYTES) { "Netinkama druska" }
                val salt = ByteArray(saltSize).also(input::readFully)
                val ivSize = input.readInt()
                require(ivSize == IV_BYTES) { "Netinkamas IV" }
                val iv = ByteArray(ivSize).also(input::readFully)
                val nameSize = input.readInt()
                require(nameSize in 1..MAX_NAME_BYTES) { "Netinkamas failo vardas" }
                val name = ByteArray(nameSize).also(input::readFully).toString(Charsets.UTF_8)
                val originalSize = input.readLong()
                require(originalSize in 0..MAX_VAULT_FILE_BYTES) { "Netinkamas pradinis dydis" }
                val safeName = FileSystemRules.validateFileName(name).getOrThrow()
                val requested = File(destinationDirectory, safeName)
                val destination = if (requested.exists()) FileSystemRules.keepBothTarget(requested) else requested
                partial = File(destinationDirectory, ".${destination.name}.partial")
                derivedKey = deriveKey(passphrase, salt, iterations)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, derivedKey, GCMParameterSpec(GCM_TAG_BITS, iv))

                var written = 0L
                BufferedOutputStream(requireNotNull(partial).outputStream()).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        operation?.checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        cipher.update(buffer, 0, read)?.let { bytes ->
                            output.write(bytes)
                            written = Math.addExact(written, bytes.size.toLong())
                        }
                        require(written <= originalSize) { "Iššifruotas turinys viršijo deklaruotą dydį" }
                        operation?.progress(byteDelta = read.toLong(), currentName = source.name)
                    }
                    val finalBytes = cipher.doFinal()
                    output.write(finalBytes)
                    written = Math.addExact(written, finalBytes.size.toLong())
                }
                require(written == originalSize) { "Iššifruoto failo dydis nesutampa" }
                require(requireNotNull(partial).renameTo(destination)) { "Iššifruoto failo užbaigti nepavyko" }
                operation?.progress(itemDelta = 1, currentName = destination.name)
                destination
            }
        } finally {
            derivedKey?.encoded?.fill(0)
            passphrase.fill('\u0000')
            partial?.takeIf(File::exists)?.delete()
        }
    }

    suspend fun inspect(source: File): VaultHeader = withContext(Dispatchers.IO) {
        DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Tai ne AF File Manager saugyklos failas" }
            input.readInt()
            val saltSize = input.readInt().also { require(it == SALT_BYTES) }
            skipFully(input, saltSize)
            val ivSize = input.readInt().also { require(it == IV_BYTES) }
            skipFully(input, ivSize)
            val nameSize = input.readInt().also { require(it in 1..MAX_NAME_BYTES) }
            val name = ByteArray(nameSize).also(input::readFully).toString(Charsets.UTF_8)
            val size = input.readLong().also { require(it in 0..MAX_VAULT_FILE_BYTES) }
            VaultHeader(name, size)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    private fun skipFully(input: DataInputStream, bytes: Int) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skipBytes(remaining)
            if (skipped <= 0) {
                require(input.read() >= 0) { "Netikėtai baigėsi saugyklos antraštė" }
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
