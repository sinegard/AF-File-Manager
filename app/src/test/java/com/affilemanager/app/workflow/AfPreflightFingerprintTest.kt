package com.affilemanager.app.workflow

import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AfPreflightFingerprintTest {
    @Test
    fun fingerprintIsStableForTheSamePreviewButChangesWithSourceContent() = runTest {
        val storage = FakeAfStorage()
        val source = storage.file("/phone/rule.txt", "first", "phone")
        val destination = storage.directory("/server", "server")
        val plan = AfPlanDefinition(
            id = "automation-plan",
            name = "Automation",
            sources = listOf(AfSourceRef(source, "rule.txt")),
            destinations = listOf(AfDestinationRef(destination)),
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SHA256,
            failurePolicy = TransferFailurePolicy.STOP,
        )
        val firstPreview = AfPlanPreflight().preview(plan, storage, nowMillis = 100)
        val firstFingerprint = AfPreflightFingerprint.create(firstPreview)

        assertEquals(firstFingerprint, AfPreflightFingerprint.create(firstPreview))

        storage.file("/phone/rule.txt", "other", "phone")
        val changedPreview = AfPlanPreflight().preview(plan, storage, nowMillis = 200)

        assertNotEquals(firstFingerprint, AfPreflightFingerprint.create(changedPreview))
    }
}
