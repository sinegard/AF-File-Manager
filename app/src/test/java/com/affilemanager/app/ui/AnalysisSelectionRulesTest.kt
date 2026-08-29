package com.affilemanager.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnalysisSelectionRulesTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun selectedFileRootCanBeModifiedButDirectoryRootCannot() {
        val directoryRoot = temporary.newFolder("directory-root")
        val child = File(directoryRoot, "child.txt").apply { writeText("child") }
        val selectedFile = temporary.newFile("selected.txt").apply { writeText("selected") }

        assertFalse(AnalysisSelectionRules.canModify(listOf(directoryRoot), directoryRoot))
        assertTrue(AnalysisSelectionRules.canModify(listOf(directoryRoot), child))
        assertTrue(AnalysisSelectionRules.canModify(listOf(selectedFile), selectedFile))
        assertFalse(AnalysisSelectionRules.canModify(listOf(selectedFile), child))
    }
}
