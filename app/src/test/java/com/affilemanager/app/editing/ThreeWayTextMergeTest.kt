package com.affilemanager.app.editing

import com.affilemanager.app.workflow.AfWorkflowLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeWayTextMergeTest {
    private val merge = ThreeWayTextMerge()

    @Test
    fun keepsTheOnlyChangedVersion() {
        val base = "one\ntwo\n"
        assertEquals("one changed\ntwo\n", merge.merge(base, "one changed\ntwo\n", base).text)
        assertEquals("one\ntwo changed\n", merge.merge(base, base, "one\ntwo changed\n").text)
    }

    @Test
    fun mergesAdjacentAndSeparatedChangesWithoutAFalseConflict() {
        val base = "one\ntwo\nthree\nfour\n"
        val yours = "ONE\ntwo\nthree\nfour\n"
        val current = "one\nTWO\nthree\nFOUR\n"

        val result = merge.merge(base, yours, current)

        assertTrue(result.conflicts.toString(), result.clean)
        assertEquals("ONE\nTWO\nthree\nFOUR\n", result.text)
    }

    @Test
    fun marksOnlyAnActuallyOverlappingEdit() {
        val result = merge.merge(
            base = "title\nbody\n",
            yours = "my title\nbody\n",
            current = "server title\nbody\n",
        )

        assertFalse(result.clean)
        assertEquals(1, result.conflicts.size)
        assertTrue(result.text.contains("<<<<<<< YOUR CHANGES"))
        assertTrue(result.text.contains("||||||| ORIGINAL"))
        assertTrue(result.text.contains(">>>>>>> CURRENT FILE"))
    }

    @Test
    fun identicalConcurrentChangeNeedsNoConflict() {
        val result = merge.merge("a\nb\n", "a\nB\n", "a\nB\n")
        assertTrue(result.clean)
        assertEquals("a\nB\n", result.text)
    }

    @Test
    fun mergesIndependentInsertionsAtDifferentAnchors() {
        val result = merge.merge(
            base = "one\ntwo\nthree\n",
            yours = "one\nyours\ntwo\nthree\n",
            current = "one\ntwo\nserver\nthree\n",
        )

        assertTrue(result.conflicts.toString(), result.clean)
        assertEquals("one\nyours\ntwo\nserver\nthree\n", result.text)
    }

    @Test
    fun identicalInsertionAtTheSamePointIsIncludedOnce() {
        val result = merge.merge(
            base = "one\ntwo\n",
            yours = "one\nshared\ntwo\n",
            current = "one\nshared\ntwo\n",
        )

        assertTrue(result.clean)
        assertEquals("one\nshared\ntwo\n", result.text)
    }

    @Test
    fun deletingARegionWhileTheServerEditsInsideItCreatesAVisibleConflict() {
        val result = merge.merge(
            base = "one\ntwo\nthree\n",
            yours = "one\nthree\n",
            current = "one\nTWO\nthree\n",
        )

        assertFalse(result.clean)
        assertEquals(1, result.conflicts.size)
        assertTrue(result.text.contains("<<<<<<< YOUR CHANGES"))
        assertTrue(result.text.contains("TWO"))
    }

    @Test
    fun preservesTextWithoutATrailingNewline() {
        val result = merge.merge("a\nb", "A\nb", "a\nB")
        assertTrue(result.clean)
        assertEquals("A\nB", result.text)
    }

    @Test
    fun rejectsInputPastTheBoundBeforeDoingDiffWork() {
        val oversized = "x".repeat(AfWorkflowLimits.MAX_TEXT_MERGE_CHARS + 1)
        val failure = runCatching { merge.merge(oversized, oversized, oversized) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
