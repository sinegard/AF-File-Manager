package com.affilemanager.app.workflow

import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AfAutomationPolicyTest {
    @Test
    fun unchangedStrongPreviewIsAllowedButAChangedSourceNeedsNewApproval() = runTest {
        val storage = FakeAfStorage()
        val source = storage.file("/phone/report.txt", "first", "phone")
        val destination = storage.directory("/server", "server")
        val plan = AfPlanDefinition(
            id = "scheduled-plan",
            name = "Scheduled",
            sources = listOf(AfSourceRef(source, "report.txt")),
            destinations = listOf(AfDestinationRef(destination)),
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SHA256,
            failurePolicy = TransferFailurePolicy.STOP,
        )
        val approved = AfPlanPreflight().preview(plan, storage, nowMillis = 10)
        val rule = AfAutomationRule(
            id = "rule-1",
            name = "Rule",
            planId = plan.id,
            enabled = true,
            schedule = AfAutomationSchedule.DAILY,
            lastPreviewFingerprint = AfPreflightFingerprint.create(approved),
        )

        assertNull(AfAutomationPolicy.runBlocker(rule, approved))

        storage.file("/phone/report.txt", "other", "phone")
        val changed = AfPlanPreflight().preview(plan, storage, nowMillis = 20)
        assertEquals(
            "Files changed; open AF Plans and approve the new preview",
            AfAutomationPolicy.runBlocker(rule, changed),
        )
    }

    @Test
    fun backgroundItemAndByteBudgetsAreHardStops() = runTest {
        val storage = FakeAfStorage()
        val source = storage.file("/phone/report.txt", "first", "phone")
        val destination = storage.directory("/server", "server")
        val plan = AfPlanDefinition(
            id = "bounded-plan",
            name = "Bounded",
            sources = listOf(AfSourceRef(source, "report.txt")),
            destinations = listOf(AfDestinationRef(destination)),
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SHA256,
            failurePolicy = TransferFailurePolicy.STOP,
        )
        val base = AfPlanPreflight().preview(plan, storage, nowMillis = 10)
        val entry = base.entries.single()

        val tooMany = base.copy(
            entries = List((AfWorkflowLimits.MAX_AUTOMATION_ITEMS + 1).toInt()) { index -> entry.copy(index = index) },
        )
        assertEquals("Preview is too large for background execution", AfAutomationPolicy.blocker(tooMany))

        val tooLarge = base.copy(projectedWriteBytes = AfWorkflowLimits.MAX_AUTOMATION_BYTES + 1)
        assertEquals("Preview exceeds the background transfer limit", AfAutomationPolicy.blocker(tooLarge))
    }

    @Test
    fun itemBudgetCountsEachProjectionOwnSourceEntriesOnly() = runTest {
        val storage = FakeAfStorage()
        val sources = (0 until 101).map { index ->
            val source = storage.file("/phone/file-$index.txt", "value-$index", "phone")
            AfSourceRef(source, "file-$index.txt")
        }
        val destination = storage.directory("/server", "server")
        val plan = AfPlanDefinition(
            id = "many-small-sources",
            name = "Many small sources",
            sources = sources,
            destinations = listOf(AfDestinationRef(destination)),
            conflictPolicy = ConflictPolicy.KEEP_BOTH,
            verification = TransferVerification.SHA256,
            failurePolicy = TransferFailurePolicy.STOP,
        )
        val preview = AfPlanPreflight().preview(plan, storage, nowMillis = 10)

        assertEquals(101, preview.entries.size)
        assertEquals(101, preview.projections.size)
        assertNull(AfAutomationPolicy.blocker(preview))
    }

    @Test
    fun workSpecificationPreservesScheduleNetworkAndChargingConstraints() {
        val disabled = AfAutomationRule(
            id = "disabled-rule",
            name = "Disabled",
            planId = "plan-1",
        )
        assertNull(AfAutomationPolicy.workSpec(disabled))

        val weekly = disabled.copy(
            id = "weekly-rule",
            enabled = true,
            schedule = AfAutomationSchedule.WEEKLY,
            unmeteredOnly = true,
            chargingOnly = true,
        ).normalized()
        val spec = requireNotNull(AfAutomationPolicy.workSpec(weekly))

        assertEquals(168L, spec.intervalHours)
        assertTrue(spec.unmeteredOnly)
        assertTrue(spec.chargingOnly)
    }
}
