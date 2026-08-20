package com.affilemanager.app.workflow

data class AfAutomationWorkSpec(
    val intervalHours: Long,
    val unmeteredOnly: Boolean,
    val chargingOnly: Boolean,
)

/** Pure, testable safety gate shared by interactive approval and background execution. */
object AfAutomationPolicy {
    fun workSpec(rule: AfAutomationRule): AfAutomationWorkSpec? {
        if (!rule.enabled || rule.schedule == AfAutomationSchedule.MANUAL_ONLY) return null
        val hours = when (rule.schedule) {
            AfAutomationSchedule.MANUAL_ONLY -> return null
            AfAutomationSchedule.EVERY_6_HOURS -> 6L
            AfAutomationSchedule.DAILY -> 24L
            AfAutomationSchedule.WEEKLY -> 24L * 7
        }
        return AfAutomationWorkSpec(hours, rule.unmeteredOnly, rule.chargingOnly)
    }

    fun blocker(preflight: AfPreflightSummary): String? {
        val entriesBySource = preflight.entries.groupingBy(AfPlannedEntry::sourceRootIndex).eachCount()
        val actionCount = preflight.projections.sumOf { projection ->
            when (projection.disposition) {
                AfPreflightDisposition.VERIFIED_IDENTICAL,
                AfPreflightDisposition.POSSIBLY_IDENTICAL,
                AfPreflightDisposition.SKIP,
                AfPreflightDisposition.BLOCKED,
                -> 1L
                else -> entriesBySource[projection.sourceRootIndex]?.toLong() ?: 0L
            }
        }
        return when {
            !preflight.canRun -> preflight.blockers.firstOrNull() ?: "Preview contains blockers"
            actionCount > AfWorkflowLimits.MAX_AUTOMATION_ITEMS ->
                "Preview is too large for background execution"
            preflight.projectedWriteBytes > AfWorkflowLimits.MAX_AUTOMATION_BYTES ->
                "Preview exceeds the background transfer limit"
            else -> null
        }
    }

    fun runBlocker(rule: AfAutomationRule, preflight: AfPreflightSummary): String? {
        blocker(preflight)?.let { return it }
        if (rule.requireFreshPreview && AfPreflightFingerprint.create(preflight) != rule.lastPreviewFingerprint) {
            return "Files changed; open AF Plans and approve the new preview"
        }
        return null
    }
}
