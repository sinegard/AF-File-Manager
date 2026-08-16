package com.affilemanager.app.advanced

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedAccessCapabilityRulesTest {
    @Test
    fun runningBinderMakesShizukuAvailableEvenWhenManagerLookupIsUnavailable() {
        assertEquals(
            CapabilityState.AVAILABLE,
            AdvancedAccessCapabilityRules.shizukuPermission(
                binderRunning = true,
                permissionGranted = false,
                permissionDenied = false,
            ),
        )
    }

    @Test
    fun stoppedBinderIsUnavailableRegardlessOfAnInstalledManager() {
        assertEquals(
            CapabilityState.UNAVAILABLE,
            AdvancedAccessCapabilityRules.shizukuPermission(
                binderRunning = false,
                permissionGranted = true,
                permissionDenied = false,
            ),
        )
    }

    @Test
    fun knownMissingRootCannotStartAConnectionAttempt() {
        assertFalse(AdvancedAccessCapabilityRules.canRequestRoot(CapabilityState.UNAVAILABLE))
        assertTrue(AdvancedAccessCapabilityRules.canRequestRoot(CapabilityState.AVAILABLE))
        assertTrue(AdvancedAccessCapabilityRules.canRequestRoot(CapabilityState.GRANTED))
    }
}
