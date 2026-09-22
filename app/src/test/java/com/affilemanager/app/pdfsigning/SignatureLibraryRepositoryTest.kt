package com.affilemanager.app.pdfsigning

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SignatureLibraryRepositoryTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun savedDrawingReloadsAndCanBeDeleted() {
        val directory = temporary.newFolder("signatures")
        val repository = SignatureLibraryRepository(directory) { 1234L }

        val saved = repository.save("Work signature", drawing()).single()
        val reloaded = SignatureLibraryRepository(directory).load().single()

        assertEquals(saved, reloaded)
        assertEquals(emptyList<SavedSignature>(), repository.delete(saved.id))
        assertEquals(emptyList<SavedSignature>(), SignatureLibraryRepository(directory).load())
    }

    @Test
    fun libraryAndNameLimitsAreEnforcedWithoutReplacingExistingData() {
        val directory = temporary.newFolder("bounded")
        val repository = SignatureLibraryRepository(directory) { 9L }
        repeat(SignatureLibraryRepository.MAX_SIGNATURES) { index -> repository.save("Signature $index", drawing()) }

        assertThrows(IllegalArgumentException::class.java) { repository.save("One too many", drawing()) }
        assertThrows(IllegalArgumentException::class.java) { repository.save(" ", drawing()) }
        assertEquals(SignatureLibraryRepository.MAX_SIGNATURES, SignatureLibraryRepository(directory).load().size)
    }

    @Test
    fun malformedLibraryIsRejectedAndNotSilentlyOverwritten() {
        val directory = temporary.newFolder("malformed")
        val file = File(directory, "signature_library_v1.json").apply { writeText("{broken") }
        val original = file.readBytes()
        val repository = SignatureLibraryRepository(directory)

        assertThrows(Exception::class.java) { repository.load() }
        assertThrows(Exception::class.java) { repository.save("New", drawing()) }
        assertEquals(original.toList(), file.readBytes().toList())
    }

    private fun drawing() = SignatureDrawing(
        listOf(
            SignatureStroke(
                listOf(
                    SignaturePoint(0.1f, 0.2f),
                    SignaturePoint(0.6f, 0.8f),
                ),
            ),
        ),
    )
}
