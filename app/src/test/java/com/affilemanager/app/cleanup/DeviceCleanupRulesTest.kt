package com.affilemanager.app.cleanup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCleanupRulesTest {
    private val now = 200L * DeviceCleanupRules.DAY_MILLIS

    @Test
    fun recentUseKeepsAnOldAppOutOfUnusedResults() {
        assertFalse(
            DeviceCleanupRules.isUnused(
                nowMillis = now,
                lastUsedMillis = now - 2 * DeviceCleanupRules.DAY_MILLIS,
                firstInstalledMillis = now - 150 * DeviceCleanupRules.DAY_MILLIS,
            ),
        )
    }

    @Test
    fun neverUsedAppMustAlsoBeOldEnough() {
        assertTrue(DeviceCleanupRules.isUnused(now, null, now - 100 * DeviceCleanupRules.DAY_MILLIS))
        assertFalse(DeviceCleanupRules.isUnused(now, null, now - 10 * DeviceCleanupRules.DAY_MILLIS))
    }
}
