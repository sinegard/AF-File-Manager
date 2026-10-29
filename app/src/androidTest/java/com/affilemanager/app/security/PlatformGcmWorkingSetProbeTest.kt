package com.affilemanager.app.security

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Diagnostic only: a bounded real-provider probe is not acceptance of arbitrary file sizes. */
class PlatformGcmWorkingSetProbeTest {
    @Test fun recordsActualPlatformUpdateBufferingAndChecksTheBoundedRoundTrip() {
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val nonce = ByteArray(12) { 3 }
        val block = ByteArray(32 * 1024) { (it % 251).toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        var encryptUpdates = 0
        val encrypted = ByteArrayOutputStream()
        repeat(32) {
            cipher.update(block)?.let { bytes -> encryptUpdates += bytes.size; encrypted.write(bytes) }
        }
        encrypted.write(cipher.doFinal())
        val encoded = encrypted.toByteArray()
        assertEquals(block.size * 32 + 16, encoded.size)
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
        decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val plain = ByteArrayOutputStream()
        var decryptUpdates = 0
        var offset = 0
        while (offset < encoded.size) {
            val count = minOf(block.size, encoded.size - offset)
            decrypt.update(encoded, offset, count)?.let { bytes -> decryptUpdates += bytes.size; plain.write(bytes) }
            offset += count
        }
        plain.write(decrypt.doFinal())
        val decoded = plain.toByteArray()
        assertEquals(block.size * 32, decoded.size)
        for (index in decoded.indices) assertEquals(block[index % block.size], decoded[index])
        println("AF_GCM_WORKING_SET_DIAGNOSTIC provider=${cipher.provider.name} inputBytes=${block.size * 32} encryptUpdateBytes=$encryptUpdates decryptUpdateBytes=$decryptUpdates")
        encoded.fill(0); decoded.fill(0); block.fill(0); nonce.fill(0)
    }
}
