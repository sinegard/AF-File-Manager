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
import com.affilemanager.app.advanced.PrivilegedFileMetadata
import com.affilemanager.app.editing.EditOrigin
import com.affilemanager.app.editing.EditSaveResult
import com.affilemanager.app.editing.EditSession
import com.affilemanager.app.editing.FileRevision
import com.affilemanager.app.ui.TerminalFailureUi
import com.affilemanager.app.ui.TerminalLocation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Invoked only by the separately signed instrumentation, never an exported component. */
@Keep
object PrivilegedTerminalRuntimeVerifier {
    @Volatile private var lastFileWriteCapability = "NOT_RUN"

    @JvmStatic fun fileWriteCapability(): String = lastFileWriteCapability

    @JvmStatic
    fun verifyAdvancedEditor(test: android.app.Instrumentation): Boolean =
        com.affilemanager.app.advanced.PrivilegedEditorRuntimeCheck.verify(test)

    @JvmStatic
    fun verifyAuthorizedBackend(context: Context, expectedUid: Int): Boolean = runBlocking {
        lastFileWriteCapability = "NOT_RUN"
        val application = context.applicationContext as AFFileManagerApplication
        val manager = application.graph.advancedAccess
        withTimeout(20_000) { manager.state.first { it.connected } }
        val binder = manager.privilegedTerminalServiceOrThrow().asBinder()
        check(verifyTransport(binder, if (expectedUid == 0) "/" else "/data/local/tmp", expectedUid))
        check(verifyReadContents(application, expectedUid))
        verifyFileMetadata(application, expectedUid)
    }

    private fun verifyReadContents(application: AFFileManagerApplication, expectedUid: Int): Boolean {
        val service = application.graph.advancedAccess.fileMetadataServiceOrThrow()
        check(service.fileReadVersion == com.affilemanager.app.advanced.PrivilegedFileServiceProtocol.READ_VERSION)
        val parent = if (expectedUid == 0) application.cacheDir else checkNotNull(application.getExternalFilesDir("validation"))
        val root = File(parent, "privileged-read-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val buffer = ByteArray(65_536) { (it % 251).toByte() }
        var accepted = false
        try {
            for (size in listOf(0, 1, 25, 65_535, 65_536, 65_537, 300_017, 4_194_304)) {
                val target = File(root, "content-$size.bin")
                val expected = MessageDigest.getInstance("SHA-256")
                target.outputStream().use { output ->
                    var remaining = size
                    while (remaining > 0) {
                        val count = minOf(remaining, buffer.size)
                        output.write(buffer, 0, count)
                        expected.update(buffer, 0, count)
                        remaining -= count
                    }
                    output.fd.sync()
                }
                val original = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
                if (expectedUid == 0) {
                    service.applyFileMetadata(target.path, original.copy(uid = 0, gid = 0,
                        mode = (original.mode and 0x1ff.inv()) or 0x180).encode())
                    check(!target.canRead())
                }
                val before = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
                val actual = MessageDigest.getInstance("SHA-256")
                var total = 0L
                application.graph.privilegedFiles.openInput(target.path).use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(count in 1..buffer.size)
                        total += count
                        check(total <= size)
                        actual.update(buffer, 0, count)
                    }
                }
                check(total == size.toLong() && MessageDigest.isEqual(expected.digest(), actual.digest()))
                check(before == PrivilegedFileMetadata.decode(service.readFileMetadata(target.path)))
                if (expectedUid == 0) service.applyFileMetadata(target.path, original.encode())
                check(target.delete())
            }
            accepted = true
            return true
        } finally {
            if (accepted) check(root.delete())
            else android.util.Log.e("AFRootAudit", root.path)
        }
    }

    private fun verifyFileMetadata(application: AFFileManagerApplication, expectedUid: Int): Boolean {
        val service = application.graph.advancedAccess.fileMetadataServiceOrThrow()
        val parent = if (expectedUid == 0) application.cacheDir else checkNotNull(application.getExternalFilesDir("validation"))
        val root = File(parent, "privileged-metadata-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        var accepted = false
        try {
            val target = File(root, "document.txt").apply { writeText("original") }
            val working = File(root, "working.txt").apply { writeText("edited through the production privileged editor") }
            if (expectedUid == 0) android.system.Os.chmod(target.path, 0x1a0)
            val before = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
            check(before.uid == android.system.Os.stat(parent.path).st_uid)
            fun revision(file: File) = FileRevision(file.length(), file.lastModified(),
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
            val original = revision(target)
            val session = EditSession(UUID.randomUUID().toString(), target.path, target.name, "text/plain", working,
                EditOrigin.Privileged(target.path, true), original, revision(working), original, true)
            val save = runCatching { application.graph.privilegedFiles.saveOrigin(session, false) }
            val failure = save.exceptionOrNull()
            if (expectedUid == 2_000 && failure is UnsupportedOperationException &&
                failure.message == "Failo prieigos teisių išlaikyti nepavyko") {
                // This exact OS denial is a supported refusal, NOT a writable backend.
                // Any other error still fails, including parser, IPC and publication errors.
                check(original.hasSameContent(revision(target)))
                check(before == PrivilegedFileMetadata.decode(service.readFileMetadata(target.path)))
                check(working.readText() == "edited through the production privileged editor")
                check(root.list()?.toSet() == setOf("document.txt", "working.txt"))
                lastFileWriteCapability = "OWNER_CHANGE_BLOCKED"
                accepted = true
                return true
            }
            check(save.getOrThrow() is EditSaveResult.Saved)
            // Read through the ordinary app process, not the privileged stream that hid this bug.
            check(target.readText() == working.readText())
            check(before.sameSecurity(PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))))
            val copy = File(root, "private-copy.txt")
            service.createPrivateEntry(copy.path, false)
            copy.writeText("copy")
            val created = PrivilegedFileMetadata.decode(service.readFileMetadata(copy.path))
            check(created.uid == before.uid && created.mode and 0x002 == 0)
            if (expectedUid == 0) check(created.mode and 0x1ff == 0x180)
            check(copy.readText() == "copy")
            service.applyFileMetadata(copy.path, before.encode())
            check(before.sameSecurity(PrivilegedFileMetadata.decode(service.readFileMetadata(copy.path))))
            val invalid = before.copy(selinuxHex = "6e6f742d612d76616c69642d6c6162656c00")
            check(runCatching { service.applyFileMetadata(copy.path, invalid.encode()) }.isFailure)
            check(target.readText() == working.readText())
            check(before.sameSecurity(PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))))
            val aborted = File(root, "aborted.txt")
            val handle = service.openPrivateWrite(aborted.path)
            try {
                check(runCatching { service.writePrivateFile(handle, ByteArray(65_537), 65_537) }.isFailure)
            } finally { service.abortPrivateWrite(handle) }
            check(aborted.length() == 0L)
            check(root.list()?.toSet() == setOf("document.txt", "working.txt", "private-copy.txt", "aborted.txt"))
            lastFileWriteCapability = "WRITE_SUPPORTED"
            accepted = true
            return true
        } finally {
            if (accepted) {
                root.listFiles()?.forEach { check(it.delete()) }
                check(root.delete())
            } else android.util.Log.e("AFRootAudit", root.path)
        }
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
