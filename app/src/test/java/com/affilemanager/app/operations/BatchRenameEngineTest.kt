package com.affilemanager.app.operations

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BatchRenameEngineTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun previewCombinesReplacementCaseNumberAndExtensionWithoutChangingFiles() = runBlocking {
        val root = temporary.newFolder("preview")
        val first = File(root, "Photo old.JPG").apply { writeText("one") }
        val second = File(root, "Photo second.JPG").apply { writeText("two") }

        val preview = BatchRenameEngine().preview(
            listOf(first.absolutePath, second.absolutePath),
            BatchRenameSpec(
                findText = "Photo ",
                replacementText = "",
                prefix = "trip-",
                caseMode = RenameCaseMode.LOWERCASE,
                numberingEnabled = true,
                numberStart = 7,
                numberPadding = 3,
                numberSeparator = "_",
                extensionOverride = "jpeg",
            ),
        )

        assertTrue(preview.canExecute)
        assertEquals(listOf("trip-old_007.jpeg", "trip-second_008.jpeg"), preview.items.map { it.targetName })
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertFalse(File(root, "trip-old_007.jpeg").exists())
    }

    @Test
    fun previewBlocksInvalidRegexDuplicateTargetsAndExistingTarget() = runBlocking {
        val root = temporary.newFolder("conflicts")
        val first = File(root, "one.txt").apply { writeText("one") }
        val second = File(root, "two.txt").apply { writeText("two") }
        File(root, "used.txt").writeText("occupied")

        val invalidRegex = BatchRenameEngine().preview(
            listOf(first.absolutePath),
            BatchRenameSpec(findText = "[", useRegex = true),
        )
        assertFalse(invalidRegex.canExecute)
        assertTrue(invalidRegex.errors.any { it.contains("reguliarioji") })

        val duplicate = BatchRenameEngine().preview(
            listOf(first.absolutePath, second.absolutePath),
            BatchRenameSpec(findText = ".*", replacementText = "same", useRegex = true),
        )
        assertFalse(duplicate.canExecute)
        assertTrue(duplicate.items.all { it.issue?.contains("tą patį") == true })

        val occupied = BatchRenameEngine().preview(
            listOf(first.absolutePath),
            BatchRenameSpec(findText = "one", replacementText = "used"),
        )
        assertFalse(occupied.canExecute)
        assertTrue(occupied.items.single().issue?.contains("naudojamas") == true)
    }

    @Test
    fun twoPhaseExecutionSupportsSwappingNamesAndUndo() = runBlocking {
        val root = temporary.newFolder("swap")
        val first = File(root, "a.txt").apply { writeText("A") }
        val second = File(root, "b.txt").apply { writeText("B") }
        val preview = BatchRenamePreview(
            listOf(
                plan(first, File(root, "b.txt")),
                plan(second, File(root, "a.txt")),
            ),
        )

        val engine = BatchRenameEngine()
        val undo = engine.execute(preview, OperationContext.background())

        assertEquals("B", File(root, "a.txt").readText())
        assertEquals("A", File(root, "b.txt").readText())

        engine.undo(undo, OperationContext.background())
        assertEquals("A", File(root, "a.txt").readText())
        assertEquals("B", File(root, "b.txt").readText())
    }

    @Test
    fun secondPhaseFailureRollsEverySourceBack() = runBlocking {
        val root = temporary.newFolder("rollback")
        val first = File(root, "one.txt").apply { writeText("one") }
        val second = File(root, "two.txt").apply { writeText("two") }
        val baseEngine = BatchRenameEngine()
        val preview = baseEngine.preview(
            listOf(first.absolutePath, second.absolutePath),
            BatchRenameSpec(prefix = "new-"),
        )
        var calls = 0
        val failingEngine = BatchRenameEngine { source, target ->
            calls += 1
            if (calls == 4) false else source.renameTo(target)
        }

        try {
            failingEngine.execute(preview, OperationContext.background())
            fail("Vykdymas turėjo nepavykti")
        } catch (_: IllegalStateException) {
            Unit
        }

        assertEquals("one", first.readText())
        assertEquals("two", second.readText())
        assertFalse(File(root, "new-one.txt").exists())
        assertFalse(File(root, "new-two.txt").exists())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".af-rename-") })
    }

    @Test
    fun undoRefusesToOverwriteWhenRenamedFileChanged() = runBlocking {
        val root = temporary.newFolder("stale-undo")
        val source = File(root, "note.txt").apply { writeText("original") }
        val engine = BatchRenameEngine()
        val preview = engine.preview(listOf(source.absolutePath), BatchRenameSpec(prefix = "new-"))
        val undo = engine.execute(preview, OperationContext.background())
        val renamed = File(root, "new-note.txt")
        renamed.writeText("changed and longer")

        val failure = runCatching { engine.undo(undo, OperationContext.background()) }.exceptionOrNull()

        assertTrue(failure?.message?.contains("pasikeitė") == true)
        assertTrue(renamed.exists())
        assertFalse(source.exists())
    }

    private fun plan(source: File, target: File) = BatchRenamePreviewItem(
        originalPath = source.absolutePath,
        originalName = source.name,
        targetPath = target.absolutePath,
        targetName = target.name,
        expectedSizeBytes = source.length(),
        expectedModifiedAtMillis = source.lastModified(),
        directory = source.isDirectory,
    )
}
