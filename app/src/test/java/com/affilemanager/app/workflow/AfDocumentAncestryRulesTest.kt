package com.affilemanager.app.workflow

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AfDocumentAncestryRulesTest {
    @Test fun neitherATruncatedGrantNorAnAlternateParentPathProvesDisjointness() {
        assertNull(AfDocumentAncestryRules.pathRelation("parent", "child", listOf("child")))
        assertEquals(true, AfDocumentAncestryRules.pathRelation("parent", "child", listOf("parent", "child")))
        assertNull(AfDocumentAncestryRules.pathRelation("parent", "other", listOf("root", "other")))
        assertEquals(false, AfDocumentAncestryRules.pathRelation("same", "same", null))
    }
    @Test fun pathRepliesAreBoundedAndMustEndAtTheRequestedIdentity() {
        assertEquals(listOf("r", "x"), AfDocumentAncestryRules.verifiedPath(listOf("r", "x"), "x"))
        for (path in listOf(emptyList(), listOf("wrong"), List(66) { "x" }, listOf("x".repeat(17000), "x"))) {
            assertTrue(runCatching { AfDocumentAncestryRules.verifiedPath(path, "x") }.isFailure)
        }
    }
    @Test fun theFallbackFindsOpaqueChildrenAndTerminatesOnCycles() = runBlocking {
        val graph = mapOf("r" to listOf("a", "b"), "a" to listOf("r", "c"), "b" to listOf("c"), "c" to emptyList())
        assertTrue(walk(graph, "c"))
        assertFalse(walk(graph, "missing"))
    }
    @Test fun entryDepthAndMetadataLimitsFailClosedRatherThanReturningFalse() = runBlocking {
        val graph = mapOf("r" to listOf("a", "b"), "a" to listOf("c"), "b" to listOf("d"))
        assertTrue(runCatching { walk(graph, "missing", entries = 3) }.isFailure)
        assertTrue(runCatching { walk(graph, "missing", depth = 1) }.isFailure)
        assertTrue(runCatching { walk(graph, "missing", bytes = 2) }.isFailure)
    }
    @Test fun cancellationStopsTheFallbackBeforeAProviderQuery() = runBlocking {
        var queried = false
        val job = launch(start = CoroutineStart.LAZY) {
            AfDocumentAncestryRules.reachable("r", "x", { it }, { true }, { 1L }, { _, _ -> queried = true; emptyList() })
        }
        job.cancel(); job.join()
        assertFalse(queried)
    }
    private suspend fun walk(graph: Map<String, List<String>>, target: String, entries: Int = 100, depth: Int = 10, bytes: Long = 1000) =
        AfDocumentAncestryRules.reachable("r", target, { it }, { graph[it]?.isNotEmpty() == true }, { 1L },
            { node, remaining -> graph[node].orEmpty().also { require(it.size <= remaining) } },
            maximumEntries = entries, maximumDepth = depth, maximumBytes = bytes)
}
