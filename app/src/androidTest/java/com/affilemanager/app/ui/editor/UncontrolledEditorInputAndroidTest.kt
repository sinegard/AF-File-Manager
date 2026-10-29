package com.affilemanager.app.ui.editor

import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.advanced.AdvancedAccessMode
import com.affilemanager.app.advanced.PrivilegedFileMetadata
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** No Compose test clock/idling rule: reproduce the normal app event ordering. */
class UncontrolledEditorInputAndroidTest {
    @Test fun rapidInputInAnOrdinaryEditorUsesTheNormalApplicationClock() = exercise(false)
    @Test fun rapidInputInAPrivilegedEditorUsesTheNormalApplicationClock() = exercise(true)

    private fun exercise(privileged: Boolean) {
        val test=InstrumentationRegistry.getInstrumentation()
        if(privileged)assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuRoot")=="true")
        val app=test.targetContext.applicationContext as AFFileManagerApplication
        val root=File(app.cacheDir,"uncontrolled-editor-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val source=File(root,"input.txt").apply { writeText("seed") }
        val activity=test.startActivitySync(Intent(Intent.ACTION_MAIN).setClass(test.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var vm:MainViewModel
        test.runOnMainSync { vm=ViewModelProvider(activity)[MainViewModel::class.java] }
        val previous=app.graph.advancedAccess.state.value.selectedMode
        var accepted=false
        fun await(block:()->Boolean) {
            val end=SystemClock.uptimeMillis()+10_000
            while(!block()) { check(SystemClock.uptimeMillis()<end); SystemClock.sleep(30) }
        }
        fun node(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
            val queue=java.util.ArrayDeque<AccessibilityNodeInfo>()
            test.uiAutomation.rootInActiveWindow?.let(queue::add)
            while(queue.isNotEmpty()) {
                val current=queue.removeFirst()
                if(predicate(current))return current
                repeat(current.childCount) { current.getChild(it)?.let(queue::add) }
            }
            return null
        }
        fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        fun nativeHistory(): org.json.JSONArray {
            val result=org.json.JSONArray()
            if(android.os.Build.VERSION.SDK_INT>=29) test.runOnMainSync {
                fun visit(view:android.view.View) {
                    if(view is CodeEditorView) {
                        val history=CodeEditorView::class.java.getDeclaredField("history").apply { isAccessible=true }.get(view) as EditorHistory
                        val input=view.getChildAt(1) as android.widget.EditText
                        result.put(org.json.JSONObject().put("text",input.text.toString()).put("focused",input.hasFocus())
                            .put("undo",history.canUndo).put("redo",history.canRedo))
                    }
                    if(view is android.view.ViewGroup)repeat(view.childCount) { visit(view.getChildAt(it)) }
                }
                android.view.inspector.WindowInspector.getGlobalWindowViews().forEach(::visit)
            }
            return result
        }
        try {
            if(privileged) {
                test.runOnMainSync { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
                await { app.graph.advancedAccess.state.value.connected }
                assertEquals(0,app.graph.advancedAccess.state.value.serviceUid)
                val service=app.graph.advancedAccess.fileMetadataServiceOrThrow()
                val original=PrivilegedFileMetadata.decode(service.readFileMetadata(source.path))
                service.applyFileMetadata(source.path,original.copy(uid=0,gid=0,mode=(original.mode and 0x1ff.inv()) or 0x180).encode())
                test.runOnMainSync { vm.openAdvancedBrowser(root.path) }
                await { !vm.advancedBrowser.value.loading && vm.advancedBrowser.value.path==root.path }
                await {
                    var candidate=node { it.text?.toString()==source.name }
                    while(candidate!=null && !candidate.isClickable)candidate=candidate.parent
                    candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
                }
            } else test.runOnMainSync { vm.open(app.graph.localFiles.toEntry(source)) }
            await { vm.fileEditState.value.text=="seed" }
            repeat(8) { round ->
                val editor=checkNotNull(node { it.className=="android.widget.EditText" })
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                await { node { it.className=="android.widget.EditText" }?.isFocused==true }
                test.uiAutomation.waitForIdle(100,3_000)
                shell("input keycombination 113 29")
                shell("input text AF_native_private_edit")
                test.uiAutomation.waitForIdle(100,3_000)
                await { vm.fileEditState.value.text=="AF_native_private_edit" &&
                    node { it.className=="android.widget.EditText" }?.text?.toString()=="AF_native_private_edit" }
                val actual=node { it.className=="android.widget.EditText" }?.text?.toString()
                File(uiAuditDirectory(app),"normal-clock-$privileged-$round.json").writeText(org.json.JSONObject()
                    .put("expected","AF_native_private_edit").put("native",actual).put("model",vm.fileEditState.value.text).toString())
                assertEquals("AF_native_private_edit",actual)
                assertEquals(actual,vm.fileEditState.value.text)
            }
            // Insert at the start so this does not merge with the existing
            // consecutive-typing group. Undo should remove just this delta.
            shell("input keyevent 122")
            test.uiAutomation.waitForIdle(100,3_000)
            for((step,command,expected) in listOf(
                Triple("append","input text Q","QAF_native_private_edit"),
                Triple("undo","input keycombination 113 54","AF_native_private_edit"),
                Triple("redo","input keycombination 113 53","QAF_native_private_edit"),
            )) {
                shell(command)
                test.uiAutomation.waitForIdle(100,3_000)
                await { vm.fileEditState.value.text==expected &&
                    node { it.className=="android.widget.EditText" }?.text?.toString()==expected }
                val actual=node { it.className=="android.widget.EditText" }?.text?.toString()
                File(uiAuditDirectory(app),"normal-history-$privileged-$step.json").writeText(org.json.JSONObject()
                    .put("expected",expected).put("native",actual).put("model",vm.fileEditState.value.text)
                    .put("undoEnabled",node { it.contentDescription?.toString()=="Undo" }?.parent?.isEnabled)
                    .put("redoEnabled",node { it.contentDescription?.toString()=="Redo" }?.parent?.isEnabled)
                    .put("views",nativeHistory()).toString())
                assertEquals(expected,actual)
                assertEquals(expected,vm.fileEditState.value.text)
            }
            for((label,expected) in listOf("Undo" to "AF_native_private_edit", "Redo" to "QAF_native_private_edit")) {
                await {
                    var action=node { it.contentDescription?.toString()==label }
                    while(action!=null && !action.isClickable)action=action.parent
                    action?.isEnabled==true && action.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                test.uiAutomation.waitForIdle(100,3_000)
                await { vm.fileEditState.value.text==expected &&
                    node { it.className=="android.widget.EditText" }?.text?.toString()==expected }
                assertEquals(label,expected,node { it.className=="android.widget.EditText" }?.text?.toString())
                assertEquals(label,expected,vm.fileEditState.value.text)
            }
            accepted=true
        } finally {
            if(!accepted) File(uiAuditDirectory(app),"normal-clock-$privileged-failure-state.json").writeText(
                org.json.JSONObject().put("model",vm.fileEditState.value.text).put("views",nativeHistory()).toString())
            if(!accepted) vm.fileEditState.value.session?.workingFile?.takeIf(File::isFile)
                ?.copyTo(File(uiAuditDirectory(app),"normal-clock-$privileged-failed-draft.txt"))
            test.runOnMainSync { vm.discardFileEditAndClose(); vm.closeAdvancedBrowser(); vm.setAdvancedAccessMode(previous) }
            if(accepted)check(root.deleteRecursively()) else android.util.Log.e("AFRootAudit",root.path)
            test.runOnMainSync { activity.finish() }
        }
    }
}
