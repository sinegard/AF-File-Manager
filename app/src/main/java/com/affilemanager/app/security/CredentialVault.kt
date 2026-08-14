package com.affilemanager.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialVault {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "af-file-manager-credentials-v1"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val VERSION: Byte = 1
    }

    fun seal(secret: CharArray): String {
        require(secret.isNotEmpty()) { "Tuščias slaptažodis nesaugomas" }
        val plain = secret.concatToString().toByteArray(Charsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plain)
            val payload = ByteBuffer.allocate(1 + IV_BYTES + encrypted.size)
                .put(VERSION)
                .put(cipher.iv)
                .put(encrypted)
                .array()
            Base64.encodeToString(payload, Base64.NO_WRAP)
        } finally {
            plain.fill(0)
            secret.fill('\u0000')
        }
    }

    fun open(payload: String): CharArray {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        require(bytes.size > 1 + IV_BYTES + 16) { "Pažeistas prisijungimo įrašas" }
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.get() == VERSION) { "Nepalaikoma prisijungimo įrašo versija" }
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plain = cipher.doFinal(encrypted)
        return try {
            plain.toString(Charsets.UTF_8).toCharArray()
        } finally {
            plain.fill(0)
            encrypted.fill(0)
            bytes.fill(0)
        }
    }

    fun destroyKey() {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}
