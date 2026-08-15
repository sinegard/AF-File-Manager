package com.affilemanager.app.terminal

import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalLimitsTest {
    @Test
    fun acceptsNormalDimensionsAndRejectsUnboundedValues() {
        TerminalLimits.requireDimensions(24, 80)
        assertThrows(IllegalArgumentException::class.java) { TerminalLimits.requireDimensions(1, 80) }
        assertThrows(IllegalArgumentException::class.java) { TerminalLimits.requireDimensions(24, 501) }
    }
}
