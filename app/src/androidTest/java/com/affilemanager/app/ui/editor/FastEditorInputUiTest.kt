package com.affilemanager.app.ui.editor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real key events, not an atomic accessibility replacement that masks lost keystrokes. */
class FastEditorInputUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun rapidHardwareTextKeepsEveryCharacterInTheViewModelAndSavedFile() = exercise(false)

    @Test fun rapidAndroidShellKeyBurstsKeepEveryCharacterWithTheKeyboardVisible() = exercise(true)

    private fun exercise(shellInput: Boolean) {
        val app=compose.activity.application as AFFileManagerApplication
        val vm=ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val root=File(app.cacheDir,"fast-editor-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val source=File(root,"fast-input.txt").apply { writeText("seed") }
        val test=InstrumentationRegistry.getInstrumentation()
        var accepted=false
        try {
            compose.runOnUiThread { vm.open(app.graph.localFiles.toEntry(source)) }
            compose.waitUntil(10_000) { vm.fileEditState.value.text=="seed" && vm.fileEditState.value.session!=null }
            repeat(4) { round ->
                compose.onNodeWithTag("full-text-editor").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("")) }
                compose.waitUntil(5_000) { vm.fileEditState.value.text=="" }
                compose.onNodeWithTag("full-text-editor").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                if(shellInput)compose.onNodeWithTag("full-text-editor").performTouchInput { click(center) }
                compose.waitForIdle()
                val expected=("AF_native_private_edit_$round ").repeat(6)
                if(shellInput) android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    test.uiAutomation.executeShellCommand("input text "+expected.replace(" ","%s")),
                ).use { it.readBytes() }
                else test.sendStringSync(expected)
                compose.waitForIdle()
                val pending=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                test.uiAutomation.rootInActiveWindow?.let(pending::add)
                var nativeText:String?=null
                while(pending.isNotEmpty()) {
                    val node=pending.removeFirst()
                    if(node.className=="android.widget.EditText")nativeText=node.text?.toString()
                    repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
                }
                File(uiAuditDirectory(app),"fast-editor-$round.json").writeText(org.json.JSONObject()
                    .put("expected",expected).put("model",vm.fileEditState.value.text).put("native",nativeText).toString())
                assertEquals("A delayed model echo must not erase hardware input",expected,nativeText)
                assertEquals(expected,vm.fileEditState.value.text)
                assertEquals("seed",source.readText())
            }
            val expected=checkNotNull(vm.fileEditState.value.text)
            compose.onNodeWithText("Save",substring=false).performClick()
            compose.waitUntil(10_000) { !vm.fileEditState.value.saving && !vm.fileEditState.value.textChanged && vm.fileEditState.value.error==null }
            assertEquals(expected,source.readText())
            val draft=checkNotNull(vm.fileEditState.value.session).workingFile
            compose.runOnUiThread { vm.closePreview() }
            compose.waitUntil(10_000) { vm.preview.value==null && !draft.exists() }
            accepted=true
        } finally {
            if(!accepted) {
                test.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(uiAuditDirectory(app),"fast-editor-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
                vm.fileEditState.value.session?.workingFile?.takeIf(File::isFile)?.copyTo(File(uiAuditDirectory(app),"failed-working-copy.txt"))
            }
            compose.runOnUiThread { vm.discardFileEditAndClose() }
            if(accepted)check(root.deleteRecursively()) else android.util.Log.e("AFRootAudit",root.path)
        }
    }
}
