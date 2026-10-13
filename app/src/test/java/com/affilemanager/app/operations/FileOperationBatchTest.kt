package com.affilemanager.app.operations

import com.affilemanager.app.model.ConflictPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOperationBatchTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun copyPublishesOneBatchOnlyAfterAllDestinationsAreCommitted() = runBlocking {
        val source = temporary.newFolder("source")
        val destination = temporary.newFolder("destination")
        val originals = (1..6).map { File(source, "$it.txt").apply { writeText("original-$it") } }
        val calls = mutableListOf<List<File>>()
        LocalFileOperator { files ->
            assertTrue(originals.all { destination.resolve(it.name).isFile })
            calls += files
        }.copyOrMove(originals.map(File::getPath), destination.path, false, ConflictPolicy.KEEP_BOTH, OperationContext.background())
        assertEquals(1, calls.size)
        assertEquals(originals.map { destination.resolve(it.name) }.toSet(), calls.single().toSet())
        originals.forEach { assertEquals(it.readText(), destination.resolve(it.name).readText()) }
    }

    @Test fun deletePublishesOneBatchAndDoesNotTouchUnselectedFiles() = runBlocking {
        val originals = (1..6).map { temporary.newFile("$it.txt").apply { writeText("owned-test") } }
        val untouched = temporary.newFile("untouched.txt").apply { writeText("original") }
        val calls = mutableListOf<List<File>>()
        LocalFileOperator { files ->
            assertTrue(originals.none(File::exists))
            calls += files
        }.deletePermanently(originals.map(File::getPath), OperationContext.background())
        assertEquals(1, calls.size)
        assertEquals(originals.toSet(), calls.single().toSet())
        assertEquals("original", untouched.readText())
    }
}
