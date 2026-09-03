package com.affilemanager.app.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalSessionStoreTest {
    @Test
    fun immediateBackendFailureStopsKeepAliveWithoutKillingTheAppProcess() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = application.graph.terminalSessions
        if (store.state.value.visible) store.closeNow()

        store.begin(
            location = TerminalLocation.PHONE,
            title = "Failing terminal",
            path = "/",
            openBackend = { throw IllegalStateException("Expected PTY failure") },
            errorInfo = {
                TerminalFailureUi(
                    title = "Expected failure",
                    detail = it.message.orEmpty(),
                    suggestion = "None",
                    diagnosticCode = "TEST-PTY-FAILURE",
                )
            },
        )

        val failed = withTimeout(10_000) {
            store.state.first { state -> state.visible && !state.starting && state.failure != null }
        }
        assertFalse(failed.running)
        assertNotNull(failed.failure)
        assertEquals("TEST-PTY-FAILURE", failed.failure?.diagnosticCode)
        store.closeNow()
    }
}
