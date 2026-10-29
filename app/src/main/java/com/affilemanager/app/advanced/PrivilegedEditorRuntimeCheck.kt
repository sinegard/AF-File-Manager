package com.affilemanager.app.advanced

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.editing.EditOrigin
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PreviewTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.UUID

/** Normal-R8 workflow check, reachable only through the existing instrumentation facade. */
internal object PrivilegedEditorRuntimeCheck {
    fun verify(test: Instrumentation): Boolean = runBlocking {
        check(android.os.Build.MODEL.contains("sdk", ignoreCase = true)) { "Owned emulator required" }
        val app = test.targetContext.applicationContext as AFFileManagerApplication
        val activity = test.startActivitySync(Intent(Intent.ACTION_MAIN)
            .setClass(test.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm: MainViewModel
        test.runOnMainSync { vm = ViewModelProvider(activity)[MainViewModel::class.java] }
        withTimeout(20_000) { app.graph.advancedAccess.state.first { it.connected } }
        check(app.graph.advancedAccess.state.value.serviceUid == 0) { "Actual UID0 provider required" }
        val service = app.graph.advancedAccess.privilegedTerminalServiceOrThrow()
        val root = File(app.cacheDir, "optimized-owner-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val evidence = File(app.getExternalFilesDir("validation"), root.name).apply { check(mkdir()) }
        var accepted = false
        try {
            for (kind in listOf("foreign-readable", "foreign-private", "app-owned")) {
                val original = "AF original $kind\n"
                val edited = "AF edited $kind\nsecond line\n"
                val target = File(root, "$kind.txt").apply { writeText(original) }
                val ordinary = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
                val foreign = kind != "app-owned"
                if (foreign) service.applyFileMetadata(target.path, ordinary.copy(uid = 0, gid = 0,
                    mode = (ordinary.mode and 0x1ff.inv()) or if (kind == "foreign-readable") 0x1b6 else 0x180).encode())
                else android.system.Os.chmod(target.path,0x1a0)
                val before = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
                check(before.uid == if (foreign) 0 else android.os.Process.myUid())
                val readable = kind != "foreign-private"
                check(target.canRead() == readable)
                test.runOnMainSync { vm.openAdvancedBrowser(root.path) }
                await { vm.advancedBrowser.value.let { it.open && !it.loading && it.path == root.path && it.error == null } }
                await { act(test, { it.text?.toString() == target.name }, ::click) }
                await { vm.fileEditState.value.let { !it.preparing && it.session != null && it.text == original } }
                val session = checkNotNull(vm.fileEditState.value.session)
                val automaticClose = vm.fileEditState.value.temporaryDownload
                if (readable) {
                    val preview = vm.preview.value
                    check(preview is PreviewTarget.LocalFile && preview.advancedOrigin) { "Direct preview routing changed" }
                } else check(vm.preview.value is PreviewTarget.PrivilegedFile)
                val value = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edited) }
                await { act(test, { it.isEditable && it.text?.toString()?.contains(original.trimEnd()) == true }) {
                    it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value)
                } }
                await { vm.fileEditState.value.text == edited && vm.fileEditState.value.textChanged }
                capture(test, File(evidence, "$kind-ready.png"))
                await { act(test, { it.text?.toString() == "Save" && it.isEnabled }, ::click) }
                await {
                    val state = vm.fileEditState.value
                    check(state.error == null) { state.error.orEmpty() }
                    !state.saving && !state.textChanged && state.conflict == null &&
                        ((state.session?.id == session.id && state.status != null) ||
                            (automaticClose && state.session == null && vm.preview.value == null && !session.workingFile.exists()))
                }
                val after = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
                val saved = app.graph.privilegedFiles.openInput(target.path).use { input ->
                    val bytes = ByteArray(edited.toByteArray(Charsets.UTF_8).size + 1)
                    var count = 0
                    while (count < bytes.size) {
                        val read = input.read(bytes, count, bytes.size - count)
                        if (read < 0) break
                        check(read > 0)
                        count += read
                    }
                    bytes.copyOf(count).toString(Charsets.UTF_8)
                }
                File(evidence, "$kind.json").writeText(JSONObject()
                    .put("before", JSONArray(before.encode().toList()))
                    .put("after", JSONArray(after.encode().toList()))
                    .put("origin", if (session.origin is EditOrigin.Privileged) "Privileged" else "Local")
                    .put("path", target.path).toString())
                check(saved == edited) { "Actual AF Save did not update the original" }
                check(before.sameSecurity(after)) { "Actual AF Save changed owner, mode or security label" }
                check(if (foreign) session.origin is EditOrigin.Privileged else session.origin is EditOrigin.Local)
                check(target.canRead() == readable)
                test.runOnMainSync { vm.closePreview() }
                await { vm.preview.value == null && !session.workingFile.exists() }
                await { vm.advancedBrowser.value.let { state ->
                    !state.loading && state.error == null && state.entries.any {
                        it.absolutePath == target.path && it.sizeBytes == edited.toByteArray(Charsets.UTF_8).size.toLong()
                    }
                } }
                test.uiAutomation.waitForIdle(250, 5_000)
                capture(test, File(evidence, "$kind-saved.png"))
            }
            check(root.list()?.toSet() == setOf("foreign-readable.txt", "foreign-private.txt", "app-owned.txt"))
            accepted = true
            true
        } finally {
            test.runOnMainSync { vm.closePreview(); vm.closeAdvancedBrowser() }
            if (accepted) {
                root.listFiles()?.forEach { check(it.delete()) }
                check(root.delete())
            } else android.util.Log.e("AFRootAudit", root.path)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Optimized AF editor workflow timed out" }
            SystemClock.sleep(50)
        }
    }

    @Suppress("DEPRECATION")
    private fun act(test: Instrumentation, matches: (AccessibilityNodeInfo) -> Boolean,
                    action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = test.uiAutomation.rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var found: AccessibilityNodeInfo? = null
        var count = 0
        try {
            while (queue.isNotEmpty()) {
                check(++count <= 512) { "Editor accessibility tree exceeded its bound" }
                val node = queue.removeFirst()
                try {
                    if (node.isVisibleToUser && matches(node)) {
                        check(found == null) { "Editor control was ambiguous" }
                        found = AccessibilityNodeInfo.obtain(node)
                    }
                    check(node.childCount <= 512 - count - queue.size) { "Editor accessibility queue exceeded its bound" }
                    for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
                } finally { node.recycle() }
            }
            return found?.let(action) ?: false
        } finally {
            found?.recycle()
            while (queue.isNotEmpty()) queue.removeFirst().recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try {
            repeat(6) {
                val observed = current ?: return false
                if (observed.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                current = observed.parent
                observed.recycle()
            }
            return false
        } finally { current?.recycle() }
    }

    private fun capture(test: Instrumentation, file: File) {
        val bitmap = checkNotNull(test.uiAutomation.takeScreenshot())
        try {
            file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
