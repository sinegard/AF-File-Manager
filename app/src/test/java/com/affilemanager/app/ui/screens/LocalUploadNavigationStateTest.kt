package com.affilemanager.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUploadNavigationStateTest {
    @Test
    fun backReturnsThroughVisitedFoldersBeforeTheDialogMayClose() {
        val initial = LocalUploadNavigationState("/storage/emulated/0/Download")
        val root = initial.navigateTo("/storage/emulated/0")
        val documents = root.navigateTo("/storage/emulated/0/Documents")

        assertTrue(documents.canNavigateBack)
        assertEquals(root, documents.navigateBack())
        assertEquals(initial, root.navigateBack())
        assertFalse(initial.canNavigateBack)
        assertEquals(initial, initial.navigateBack())
    }

    @Test
    fun openingTheCurrentFolderDoesNotCreateDuplicateHistory() {
        val state = LocalUploadNavigationState("/storage/emulated/0/Download")

        assertEquals(state, state.navigateTo(state.currentPath))
    }
}
