package com.affilemanager.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    @Test
    fun keystoreRoundTripWipesCallerSecret() {
        val vault = CredentialVault()
        val secret = "emuliatoriaus-slaptažodis".toCharArray()
        val sealed = vault.seal(secret)

        assertTrue(secret.all { it == '\u0000' })
        val opened = vault.open(sealed)
        assertArrayEquals("emuliatoriaus-slaptažodis".toCharArray(), opened)
        opened.fill('\u0000')
    }
}
