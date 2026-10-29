package com.affilemanager.app.security

import kotlinx.coroutines.runBlocking
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.operations.OperationSnapshot
import com.affilemanager.app.operations.OperationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileVaultSafetyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun encryptionCannotSilentlyReplaceAnExistingVault() = runBlocking {
        val source = temporary.newFile("document.txt").apply { writeText("new contents") }
        val existing = temporary.newFile("document.txt.afvault").apply { writeText("previous vault must remain") }
        val before = existing.readBytes()
        val failure = runCatching { FileVaultEngine().encrypt(source, existing, "test-only-key".toCharArray()) }.exceptionOrNull()
        assertArrayEquals("Existing vault was overwritten without a conflict choice", before, existing.readBytes())
        assertNotNull("An occupied explicit destination needs a failure", failure)
        assertEquals("new contents", source.readText())
    }

    @Test fun anExistingHiddenPartialFileIsNotEncryptionStaging() = runBlocking {
        val source = temporary.newFile("document.txt").apply { writeText("input") }
        val destination = File(temporary.root, "document.afvault")
        val unrelated = temporary.newFile(".document.afvault.partial").apply { writeText("not an AF-owned temporary file") }
        FileVaultEngine().encrypt(source, destination, "test-only-key".toCharArray())
        assertTrue("Encryption removed an unrelated hidden file", unrelated.isFile)
        assertEquals("not an AF-owned temporary file", unrelated.readText())
        assertTrue(destination.isFile)
    }

    @Test fun anExistingHiddenPartialFileIsNotDecryptionStaging() = runBlocking {
        val source = temporary.newFile("document.txt").apply { writeText("input") }
        val vault = File(temporary.root, "document.afvault")
        val engine = FileVaultEngine()
        engine.encrypt(source, vault, "test-only-key".toCharArray())
        val output = temporary.newFolder("output")
        val unrelated = File(output, ".document.txt.partial").apply { writeText("not an AF-owned temporary file") }
        val decrypted = engine.decrypt(vault, output, "test-only-key".toCharArray())
        assertTrue("Decryption removed an unrelated hidden file", unrelated.isFile)
        assertEquals("not an AF-owned temporary file", unrelated.readText())
        assertEquals("input", decrypted.readText())
    }

    @Test fun encryptingOverTheSourceIsRejectedWithoutChangingIt() = runBlocking {
        val source = temporary.newFile("document.txt").apply { writeText("original") }
        val failure = runCatching { FileVaultEngine().encrypt(source, source, "test-only-key".toCharArray()) }.exceptionOrNull()
        assertEquals("Encryption replaced its own original", "original", source.readText())
        assertNotNull(failure)
    }

    @Test fun aChangingSourceDoesNotProduceASuccessfullyPublishedUnusableVault() = runBlocking {
        val source = temporary.newFile("document.txt").apply { writeText("original") }
        val destination = File(temporary.root, "document.afvault")
        var changed = false
        val operation = OperationContext("changing-source", MutableStateFlow(false)) { transform ->
            val update = OperationSnapshot("changing-source", "test", OperationStatus.RUNNING).transform()
            if (!changed && update.completedBytes > 0) { source.appendText(" changed"); changed = true }
        }
        val failure = runCatching { FileVaultEngine().encrypt(source, destination, "test-only-key".toCharArray(), operation) }.exceptionOrNull()
        assertNotNull("Changed input was published with a stale size header", failure)
        assertFalse(destination.exists())
        assertEquals("original changed", source.readText())
        assertEquals(listOf(source.name), temporary.root.list()!!.toList())
    }
}
