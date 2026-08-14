package com.affilemanager.app.network

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.security.CredentialVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkProfileStoreTest {
    @Test
    fun editPreservesBlankSecretAndReplacesExplicitSecret() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("network_profiles_v1", android.content.Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        val store = NetworkProfileStore(context, CredentialVault())
        try {
            val initialPassword = "old-secret".toCharArray()
            val created = store.save(profile(), initialPassword, null).getOrThrow()
            assertTrue(initialPassword.all { it == '\u0000' })

            val edited = created.copy(name = "Office NAS", host = "nas.example.test", port = 2222)
            val saved = store.save(edited, null, null).getOrThrow()
            assertEquals(created.id, saved.id)
            assertEquals("Office NAS", saved.name)
            assertEquals("nas.example.test", saved.host)
            store.secret(saved.id).getOrThrow().use { secret ->
                assertEquals("old-secret", secret.password.concatToString())
            }

            val replacement = "new-secret".toCharArray()
            store.save(saved, replacement, null).getOrThrow()
            assertTrue(replacement.all { it == '\u0000' })
            store.secret(saved.id).getOrThrow().use { secret ->
                assertEquals("new-secret", secret.password.concatToString())
            }
            assertEquals(1, store.list().size)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun multilineServerIsRejectedWithoutPersistingOrRetainingPlainSecret() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val preferences = context.getSharedPreferences("network_profiles_v1", android.content.Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        val store = NetworkProfileStore(context, CredentialVault())
        val marker = "TOP_SECRET_MARKER"
        val password = "temporary-secret".toCharArray()
        try {
            val result = store.save(profile().copy(host = "192.0.2.10\naccount\n$marker"), password, null)

            assertTrue(result.isFailure)
            assertTrue(marker !in result.exceptionOrNull()?.message.orEmpty())
            assertTrue(password.all { it == '\u0000' })
            assertTrue(store.list().isEmpty())
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private fun profile() = NetworkProfile(
        id = "",
        name = "Test server",
        protocol = NetworkProtocol.SFTP,
        host = "server.example.test",
        port = 22,
        username = "tester",
        basePath = "/files",
        expectedHostKeySha256 = null,
        allowFirstUseTrust = true,
    )
}
