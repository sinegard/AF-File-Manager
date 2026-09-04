package com.affilemanager.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshRequestRulesTest {
    @Test
    fun coalescesARefreshWhileOneIsAlreadyRunning() {
        assertTrue(RefreshRequestRules.canStart(isRefreshing = false))
        assertFalse(RefreshRequestRules.canStart(isRefreshing = true))
    }
}
