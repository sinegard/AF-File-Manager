package com.affilemanager.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedBrowserBackRulesTest {
    private val roots = listOf(
        "/storage/emulated/0/Android/data",
        "/storage/emulated/0/Android/obb",
    )

    @Test
    fun historyTakesPriorityAndIsConsumed() {
        val decision = AdvancedBrowserBackRules.decide(
            currentPath = "${roots.first()}/app/files",
            backHistory = listOf(roots.first(), "${roots.first()}/app"),
            allowedRoots = roots,
        )

        assertEquals("${roots.first()}/app", decision.targetPath)
        assertTrue(decision.consumeHistory)
    }

    @Test
    fun directDeepLinkMovesToAllowedParentBeforeClosing() {
        val decision = AdvancedBrowserBackRules.decide(
            currentPath = "${roots.first()}/app/files",
            backHistory = emptyList(),
            allowedRoots = roots,
        )

        assertEquals("${roots.first()}/app", decision.targetPath)
        assertFalse(decision.consumeHistory)
    }

    @Test
    fun protectedRootClosesAndStalePathsCannotEscapeNewBoundary() {
        assertNull(
            AdvancedBrowserBackRules.decide(roots.first(), emptyList(), roots).targetPath,
        )
        assertNull(
            AdvancedBrowserBackRules.decide(
                currentPath = "/system",
                backHistory = listOf("/vendor"),
                allowedRoots = roots,
            ).targetPath,
        )
    }
}
