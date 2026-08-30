package com.affilemanager.app.ui.screens

import com.affilemanager.app.model.DuplicateGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DuplicateCleanupSelectionRulesTest {
    @Test
    fun leavesExactlyOneCopyUnselectedInEveryGroup() {
        val groups = listOf(
            DuplicateGroup("a", 10, listOf("/b/two.txt", "/a/one.txt", "/c/three.txt")),
            DuplicateGroup("b", 20, listOf("/z/last.jpg", "/m/first.jpg")),
        )

        val selected = DuplicateCleanupSelectionRules.copiesToSelect(groups)

        assertEquals(setOf("/b/two.txt", "/c/three.txt", "/z/last.jpg"), selected)
        assertFalse("/a/one.txt" in selected)
        assertFalse("/m/first.jpg" in selected)
    }

    @Test
    fun repeatedAndBlankPathsCannotRemoveTheOnlyCopy() {
        val groups = listOf(
            DuplicateGroup("a", 10, listOf("", "/only.txt", "/only.txt")),
            DuplicateGroup("b", 10, listOf("/keep.txt", "/copy.txt", "/copy.txt")),
        )

        assertEquals(setOf("/keep.txt"), DuplicateCleanupSelectionRules.copiesToSelect(groups))
    }

    @Test
    fun smartSelectionReplacesManualDuplicateChoicesButKeepsOtherSelections() {
        val group = DuplicateGroup("a", 10, listOf("/a.txt", "/b.txt", "/c.txt"))

        val selected = DuplicateCleanupSelectionRules.selectCopies(
            currentSelection = setOf("/a.txt", "/b.txt", "/c.txt", "/unrelated.txt"),
            groups = listOf(group),
        )

        assertEquals(setOf("/b.txt", "/c.txt", "/unrelated.txt"), selected)
    }
}
