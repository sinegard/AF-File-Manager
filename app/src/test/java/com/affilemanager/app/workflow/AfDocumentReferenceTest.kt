package com.affilemanager.app.workflow

import com.affilemanager.app.model.ConflictPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AfDocumentReferenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val uri = "content://com.android.externalstorage.documents/tree/ABCD-1234%3A"

    @Test fun opaqueProviderAnchorAndRelativeNamesRemainSeparate() {
        val root = AfLocationRef.document(uri)
        val child = AfWorkflowPaths.child(root, "folder with spaces/ž.txt")
        assertEquals(uri, child.path)
        assertEquals("folder with spaces/ž.txt", child.documentPath)
        assertEquals("folder with spaces", AfWorkflowPaths.parent(child).documentPath)
        assertNotEquals(root.identityKey(), child.identityKey())
        assertTrue(runCatching { AfWorkflowPaths.parent(root) }.isFailure)
    }

    @Test fun invalidGrantAddressesAndEscapingPathsAreRejected() {
        listOf("file:///storage/ABCD-1234", "https://example.com/tree/a", "$uri?token=private", "$uri#fragment").forEach {
            assertTrue(it, runCatching { AfLocationRef.document(it) }.isFailure)
        }
        listOf("../outside", "a/../../b", "/absolute", "a//b", "a\\b", "bad\u0000name").forEach {
            assertTrue(it, runCatching { AfLocationRef.document(uri, it) }.isFailure)
        }
    }

    @Test fun mixedDocumentPlansRoundTripWithAVersionedCollectionAndOldPlansSurvive() {
        val root = temporary.newFolder()
        val repository = AfPlanRepository(root)
        val local = AfPlanDefinition(id = "old-plan", name = "Original",
            sources = listOf(AfSourceRef(AfLocationRef.local(root.path), "source")),
            destinations = listOf(AfDestinationRef(AfLocationRef.local(requireNotNull(root.parent)))), conflictPolicy = ConflictPolicy.KEEP_BOTH)
        repository.save(local)
        val path = File(root, "af_workflows_v1/plans.json")
        assertEquals(1, JSONObject(path.readText()).getInt("schemaVersion"))
        val documents = local.copy(id = "document-plan", name = "SD",
            destinations = listOf(AfDestinationRef(AfLocationRef.document(uri, "target"))))
        val saved = repository.save(documents)
        assertEquals(2, JSONObject(path.readText()).getInt("schemaVersion"))
        assertEquals(saved, repository.find(saved.id))
        assertNotNull(repository.find(local.id))
        val ordinary = repository.save(local.copy(id = "ordinary-paste", fileManagerTransfer = true))
        assertTrue(requireNotNull(repository.find(ordinary.id)).fileManagerTransfer)
        assertFalse(requireNotNull(repository.find(local.id)).fileManagerTransfer)
        val json = JSONObject(path.readText())
        json.put("schemaVersion", 999)
        path.writeText(json.toString())
        val preserved = path.readBytes()
        assertTrue(runCatching { repository.list() }.isFailure)
        assertArrayEquals(preserved, path.readBytes())
        assertEquals(0, File(root, "af_workflows_v1/corrupt").list()?.size)
    }
}
