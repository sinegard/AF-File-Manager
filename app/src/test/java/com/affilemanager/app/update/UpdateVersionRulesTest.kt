package com.affilemanager.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionRulesTest {
    @Test
    fun comparesStableSemanticVersions() {
        assertTrue(UpdateVersionRules.isNewer("v0.9.5", "0.9.4"))
        assertTrue(UpdateVersionRules.isNewer("1.0.0", "0.99.99-debug"))
        assertFalse(UpdateVersionRules.isNewer("v0.9.4", "0.9.4-debug"))
        assertFalse(UpdateVersionRules.isNewer("0.9.3", "0.9.4"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAmbiguousVersion() {
        UpdateVersionRules.normalized("release-latest")
    }
}
