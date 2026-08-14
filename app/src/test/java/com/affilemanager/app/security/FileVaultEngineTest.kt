package com.affilemanager.app.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileVaultEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun encryptedVaultRoundTripAndWrongPasswordFailure() = runBlocking {
        val source = temporary.newFile("pastaba.txt").apply { writeText("Privatus tekstas ąčęėįšųūž") }
        val vault = File(temporary.root, "pastaba.txt.afvault")
        val engine = FileVaultEngine()

        engine.encrypt(source, vault, "ilga-slaptafraze".toCharArray())
        assertTrue(vault.isFile)
        assertFalse(vault.readBytes().toString(Charsets.UTF_8).contains("Privatus tekstas"))
        val header = engine.inspect(vault)
        assertEquals("pastaba.txt", header.originalName)
        assertEquals(source.length(), header.originalSize)

        source.delete()
        val failure = runCatching { engine.decrypt(vault, temporary.root, "blogas-raktas".toCharArray()) }
        assertTrue(failure.isFailure)
        assertFalse(File(temporary.root, ".pastaba.txt.partial").exists())

        val restored = engine.decrypt(vault, temporary.root, "ilga-slaptafraze".toCharArray())
        assertEquals("Privatus tekstas ąčęėįšųūž", restored.readText())
    }
}
