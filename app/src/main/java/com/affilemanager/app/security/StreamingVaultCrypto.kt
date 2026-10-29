package com.affilemanager.app.security

import com.google.crypto.tink.AccessesPartialKey
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes

/** Password keys exist only for this operation; no Android/persistent keyset is created. */
internal object StreamingVaultCrypto {
    init { StreamingAeadConfig.register() }

    @AccessesPartialKey
    fun primitive(passwordKey: ByteArray): StreamingAead {
        val key = AesGcmHkdfStreamingKey.create(
            PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB,
            SecretBytes.copyFrom(passwordKey, InsecureSecretKeyAccess.get()),
        )
        return KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.importKey(key).withFixedId(1).makePrimary())
            .build()
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }
}
