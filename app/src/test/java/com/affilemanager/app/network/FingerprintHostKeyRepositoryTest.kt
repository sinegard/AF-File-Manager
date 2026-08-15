package com.affilemanager.app.network

import com.jcraft.jsch.HostKeyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FingerprintHostKeyRepositoryTest {
    private val key = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun firstUseIsExplicitAndProducesAPinnableFingerprint() {
        val trusted = FingerprintHostKeyRepository(expected = null, allowFirstUse = true)
        val denied = FingerprintHostKeyRepository(expected = null, allowFirstUse = false)

        assertEquals(HostKeyRepository.OK, trusted.check("host", key))
        assertNotNull(trusted.observed)
        assertEquals(HostKeyRepository.NOT_INCLUDED, denied.check("host", key))
    }

    @Test
    fun matchingPinIsAcceptedAndChangedKeyIsBlocked() {
        val first = FingerprintHostKeyRepository(expected = null, allowFirstUse = true)
        first.check("host", key)
        val expected = requireNotNull(first.observed)

        assertEquals(
            HostKeyRepository.OK,
            FingerprintHostKeyRepository(expected, allowFirstUse = false).check("host", key),
        )
        assertEquals(
            HostKeyRepository.CHANGED,
            FingerprintHostKeyRepository(expected, allowFirstUse = false).check("host", byteArrayOf(9, 8, 7)),
        )
    }
}
