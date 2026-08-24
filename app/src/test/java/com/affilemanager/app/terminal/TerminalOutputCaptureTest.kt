package com.affilemanager.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputCaptureTest {
    @Test
    fun keepsOnlyTheMostRecentSubmittedCommandOutput() {
        val capture = TerminalOutputCapture()

        capture.recordAcceptedInput("first\r".toByteArray())
        val firstOutput = "first\r\none\r\n/ $ ".toByteArray()
        capture.recordOutput(firstOutput, length = firstOutput.size)
        capture.recordAcceptedInput("second\r".toByteArray())
        val output = "second\r\n\u001B[32mtwo\u001B[0m\r\n/ $ ".toByteArray()
        capture.recordOutput(output, length = output.size)

        val snapshot = capture.snapshot()
        assertEquals("two", snapshot?.text)
        assertFalse(snapshot?.truncated ?: true)
    }

    @Test
    fun handlesPowerShellPromptAndUtf8Output() {
        val capture = TerminalOutputCapture()
        capture.recordAcceptedInput("Write-Output labas\r".toByteArray())
        val output = "Write-Output labas\r\nŽinutė\r\nPS C:\\Users\\test> ".toByteArray()
        capture.recordOutput(output, length = output.size)

        assertEquals("Žinutė", capture.snapshot()?.text)
    }

    @Test
    fun staysIdleUntilACommandIsSubmittedAndReportsTruncation() {
        val capture = TerminalOutputCapture(maximumCaptureBytes = 8)
        val prompt = "/ $ ".toByteArray()
        capture.recordOutput(prompt, length = prompt.size)
        assertNull(capture.snapshot())

        capture.recordAcceptedInput("go\r".toByteArray())
        val output = "go\r\n12345".toByteArray()
        capture.recordOutput(output, length = output.size)

        assertTrue(capture.snapshot()?.truncated == true)
    }
}
