package com.affilemanager.app.media.editing

import org.junit.Assert.*
import org.junit.Test

class NativeMemoryObservationTest {
    @Test fun fastWorkerExecAndExitDoNotBecomeFalseMemoryErrors() {
        assertNull(NativeMemoryObservation.rss("State:\tR (running)\n",true,20,"probe"))
        assertNull(NativeMemoryObservation.rss("State:\tZ (zombie)\n",true,3000,"probe"))
        assertNull(NativeMemoryObservation.rss("State:\tX (dead)\n",true,3000,"probe"))
        assertNull(NativeMemoryObservation.rss(null,false,3000,"probe"))
        assertEquals(12345L,NativeMemoryObservation.rss("State:\tR\nVmRSS:\t12345\tkB\n",true,3000,"probe"))
    }
    @Test fun liveWorkerWithNoMeterStillFailsAfterTheExistingStartupDeadline() {
        val failure=assertThrows(NativeMediaFailure::class.java){NativeMemoryObservation.rss("State:\tR\n",true,2001,"probe")}
        assertEquals("AF_MEDIA_MEMORY_UNAVAILABLE",failure.code)
    }
}
