package com.affilemanager.app.advanced

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivilegedPathRulesTest {
    private val roots = listOf("/storage/emulated/0/Android/data", "/storage/emulated/0/Android/obb")

    @Test
    fun normalizesOnlyWithinExplicitAllowedRoots() {
        assertEquals(
            "/storage/emulated/0/Android/data/app/files",
            PrivilegedPathRules.requireWithinAllowed("/storage/emulated/0/Android/data/app/./files", roots),
        )
        assertThrows(SecurityException::class.java) {
            PrivilegedPathRules.requireWithinAllowed("/storage/emulated/0/Android/database/secret", roots)
        }
        assertThrows(SecurityException::class.java) {
            PrivilegedPathRules.requireWithinAllowed("/storage/emulated/0/Download", roots)
        }
    }

    @Test
    fun rootMutationTraversalAndInjectedNamesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegedPathRules.requireWithinAllowed(roots.first(), roots, allowRoot = false)
        }
        assertThrows(SecurityException::class.java) {
            PrivilegedPathRules.requireWithinAllowed("/storage/emulated/0/Android/data/../../Download", roots)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegedPathRules.child(roots.first(), "../escape", roots)
        }
    }

    @Test
    fun parentStopsAtProtectedRoot() {
        assertNull(PrivilegedPathRules.parent(roots.first(), roots))
        assertEquals(roots.first(), PrivilegedPathRules.parent("${roots.first()}/app", roots))
    }

    @Test
    fun filesystemRootAllowsDescendantsButCannotBeMutated() {
        val rootAccess = listOf("/")

        assertEquals(
            "/storage/emulated/0",
            PrivilegedPathRules.requireWithinAllowed("/storage/emulated/0", rootAccess),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegedPathRules.requireWithinAllowed("/", rootAccess, allowRoot = false)
        }
        assertEquals("/storage", PrivilegedPathRules.parent("/storage/emulated", rootAccess))
        assertNull(PrivilegedPathRules.parent("/", rootAccess))
    }
}
