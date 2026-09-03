package com.affilemanager.app.terminal

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import androidx.annotation.Keep
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.advanced.IPrivilegedFileService
import com.affilemanager.app.advanced.RootFileService
import com.affilemanager.app.ui.TerminalFailureUi
import com.affilemanager.app.ui.TerminalLocation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream

/** Invoked only by the separately signed instrumentation, never an exported component. */
@Keep
object PrivilegedTerminalRuntimeVerifier {
    @JvmStatic
    fun verifyAuthorizedBackend(context: Context, expectedUid: Int): Boolean = runBlocking {
        val application = context.applicationContext as AFFileManagerApplication
        val manager = application.graph.advancedAccess
        withTimeout(20_000) { manager.state.first { it.connected } }
        val binder = manager.privilegedTerminalServiceOrThrow().asBinder()
        verifyTransport(binder, if (expectedUid == 0) "/" else "/data/local/tmp", expectedUid)
    }

    @JvmStatic
    fun verifyRootServiceContract(context: Context): Boolean = runBlocking {
        // Exercise the RootService wrapper in the app UID without claiming that the
        // device grants this app su. The separate authorized-backend test checks UID 0.
        val binder = RootFileService().onBind(Intent())
        try {
            verifyTransport(binder, context.cacheDir.canonicalPath, Process.myUid())
        } finally {
            IPrivilegedFileService.Stub.asInterface(binder).destroy()
        }
    }

    private suspend fun verifyTransport(binder: IBinder, path: String, expectedUid: Int): Boolean {
        // Force the generated Proxy/Parcel path even if instrumentation shares a process.
        val remoteBinder = object : IBinder by binder {
            override fun queryLocalInterface(descriptor: String): IInterface? = null
        }
        val service = IPrivilegedFileService.Stub.asInterface(remoteBinder)
        check(service.processUid == expectedUid) { "Privileged backend has a different UID" }
        val backend = PrivilegedPtyBackend.open(service, path)
        return try {
            backend.resize(30, 100)
            val marker = "AF_PRIVILEGED_CHECK_${System.nanoTime()}"
            backend.write("pwd; id; printf '$marker\\n'\n".toByteArray())
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1_024)
            withTimeout(10_000) {
                while (true) {
                    val count = backend.read(buffer)
                    check(count >= 0) { "Privileged PTY ended before returning output" }
                    if (count > 0) {
                        check(output.size() + count <= 64 * 1_024) { "Privileged PTY output exceeded the test limit" }
                        output.write(buffer, 0, count)
                        if (output.toString(Charsets.UTF_8.name()).lineSequence().any { it.trimEnd('\r') == marker }) break
                    }
                }
            }
            val text = output.toString(Charsets.UTF_8.name())
            check(text.contains("uid=$expectedUid(")) { "PTY did not use the authorized service UID" }
            check(text.lineSequence().any { it.trimEnd('\r') == path }) { "PTY did not use the requested working directory" }
            val rejected = runCatching { service.openTerminal("/af-missing-${System.nanoTime()}", 24, 80) }
            check(rejected.isFailure) { "An unavailable PTY directory was accepted" }
            true
        } finally {
            backend.close()
        }
    }

    @JvmStatic
    fun verifyImmediateFailure(context: Context): Boolean = runBlocking {
        val application = context.applicationContext as AFFileManagerApplication
        val store = application.graph.terminalSessions
        check(!store.state.value.visible) { "Close the existing terminal before this test" }
        try {
            store.begin(
                location = TerminalLocation.PHONE, title = "Terminal startup check", path = "/",
                openBackend = { throw IllegalStateException("Expected PTY startup failure") },
                errorInfo = { TerminalFailureUi("Expected failure", "", "", "TEST-PTY-FAILURE") },
            )
            val failed = withTimeout(10_000) { store.state.first { it.visible && !it.starting && it.failure != null } }
            check(!failed.running && failed.failure?.diagnosticCode == "TEST-PTY-FAILURE")
            store.closeNow()
            // Android's missed startForeground deadline is asynchronous; returning before it
            // expires would hide the crash this regression check is intended to catch.
            delay(12_000)
            !store.state.value.visible
        } finally {
            if (store.state.value.visible) store.closeNow()
        }
    }
}
