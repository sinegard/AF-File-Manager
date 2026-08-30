package com.affilemanager.app.ui

import com.affilemanager.app.advanced.PrivilegedPathRules

internal data class AdvancedBackDecision(
    val targetPath: String?,
    val consumeHistory: Boolean,
)

internal object AdvancedBrowserBackRules {
    fun decide(
        currentPath: String,
        backHistory: List<String>,
        allowedRoots: List<String>,
    ): AdvancedBackDecision {
        backHistory.lastOrNull()?.let { previous ->
            val validated = runCatching {
                PrivilegedPathRules.requireWithinAllowed(previous, allowedRoots)
            }.getOrNull()
            if (validated != null) {
                return AdvancedBackDecision(targetPath = validated, consumeHistory = true)
            }
        }
        return AdvancedBackDecision(
            targetPath = runCatching { PrivilegedPathRules.parent(currentPath, allowedRoots) }.getOrNull(),
            consumeHistory = false,
        )
    }
}
