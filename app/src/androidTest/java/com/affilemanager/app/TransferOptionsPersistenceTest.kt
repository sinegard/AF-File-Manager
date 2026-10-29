package com.affilemanager.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.input.key.Key
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.data.TransferOptions
import com.affilemanager.app.data.TransferPreferences
import com.affilemanager.app.operations.DurableTransferStatus
import com.affilemanager.app.operations.TransferVerification
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TransferOptionsPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MainViewModel
    private lateinit var original: TransferPreferences

    @Before fun resetOnlyTransferChoices() {
        vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        original = vm.transferPreferences.value
        compose.runOnUiThread {
            check(vm.saveTransferOptions(false, TransferOptions.defaults(false)))
            check(vm.saveTransferOptions(true, TransferOptions.defaults(true)))
        }
    }

    @After fun restoreTransferChoices() {
        compose.runOnUiThread {
            check(vm.saveTransferOptions(false, original.copy))
            check(vm.saveTransferOptions(true, original.move))
        }
    }

    @Test fun moveChoicesSurviveTheNextPaste() {
        val app = compose.activity.application as AFFileManagerApplication
        val root = File(app.getExternalFilesDir("validation"), "transfer-options-${System.nanoTime()}").apply { check(mkdirs()) }
        val source = File(root, "source").apply { check(mkdir()) }
        val target = File(root, "target").apply { check(mkdir()) }
        val first = File(source, "first.txt").apply { writeText("owned first fixture") }
        val second = File(source, "second.txt").apply { writeText("owned second fixture") }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            cutAndOpenPaste(vm, source, target, first)
            compose.onNodeWithTag("transfer_options_dialog").assertExists()
            compose.onNodeWithTag("transfer_options_sha256").performScrollTo().assertIsDisplayed().assertIsOn().performClick().assertIsOff()
            compose.onNodeWithText("Save plan and start").performClick()
            compose.waitUntil(10_000) { !first.exists() && File(target, first.name).isFile && vm.clipboard.value == null }
            cutAndOpenPaste(vm, source, target, second)
            compose.onNodeWithTag("transfer_options_dialog").assertExists()
            // A different source creates a new plan, but must not reset the chosen verification.
            compose.onNodeWithTag("transfer_options_sha256").performScrollTo().assertIsDisplayed().assertIsOff()
            compose.onNodeWithText("Cancel").performClick()
            check(second.readText() == "owned second fixture")
        } finally {
            compose.runOnUiThread { vm.navigate(PanelId.LEFT, root.parentFile!!.path) }
            check(root.deleteRecursively())
        }
    }

    @Test fun rememberedCopyCreatesFreshPlansAndLeavesBothSourcesUnchanged() = verifyAutomaticPaste(false)

    @Test fun rememberedMoveCreatesFreshPlansAndRemovesOnlyVerifiedSources() = verifyAutomaticPaste(true)

    @Test fun keyboardPasteUsesTheSameRememberedOptionsAsTheMenu() {
        val app = compose.activity.application as AFFileManagerApplication
        val root = File(app.getExternalFilesDir("validation"), "keyboard-paste-${System.nanoTime()}").apply { check(mkdirs()) }
        val source = File(root, "source").apply { check(mkdir()) }
        val target = File(root, "target").apply { check(mkdir()) }
        val input = File(source, "keyboard.txt").apply { writeText("keyboard copy original") }
        try {
            compose.runOnUiThread { vm.activatePanel(PanelId.LEFT); vm.navigate(PanelId.LEFT, source.path) }
            compose.waitUntil(10_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.entries.any { it.absolutePath == input.path } }
            compose.runOnUiThread {
                check(vm.saveTransferOptions(false, TransferOptions.defaults(false).copy(askBeforeTransfer = false)))
                vm.selectOnly(PanelId.LEFT, input.path)
                vm.copySelection(PanelId.LEFT, move = false)
                vm.navigate(PanelId.LEFT, target.path)
            }
            compose.waitUntil(10_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.path == target.path }
            // Programmatic navigation does not move keyboard focus into the
            // browser. Focus its action button before exercising its shortcut.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
            compose.onNodeWithContentDescription("Folder actions")
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
                .assertIsFocused()
            compose.onRoot().performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.V); keyUp(Key.CtrlLeft) }
            compose.onNodeWithTag("transfer_options_dialog").assertDoesNotExist()
            compose.waitUntil(10_000) { File(target, input.name).isFile }
            assertEquals("keyboard copy original", input.readText())
            assertEquals("keyboard copy original", File(target, input.name).readText())
        } finally {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
            compose.runOnUiThread { vm.navigate(PanelId.LEFT, root.parentFile!!.path) }
            check(root.deleteRecursively())
        }
    }

    private fun verifyAutomaticPaste(moving: Boolean) {
        val app = compose.activity.application as AFFileManagerApplication
        val root = File(app.getExternalFilesDir("validation"), "automatic-paste-${System.nanoTime()}").apply { check(mkdirs()) }
        val source = File(root, "source").apply { check(mkdir()) }
        val firstTarget = File(root, "target-one").apply { check(mkdir()) }
        val secondTarget = File(root, "target-two").apply { check(mkdir()) }
        val first = File(source, "first.txt").apply { writeText("first owned copy") }
        val second = File(source, "second.txt").apply { writeText("second owned copy") }
        val outside = File(root, "not-selected.txt").apply { writeText("never selected") }
        try {
            cutAndOpenPaste(vm, source, firstTarget, first, moving)
            compose.onNodeWithTag("transfer_options_dialog").assertExists()
            if (!moving) compose.onNodeWithTag("transfer_options_sha256").performScrollTo().assertIsDisplayed().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithTag("transfer_options_ask").performScrollTo().performClick()
            compose.onNodeWithTag("transfer_options_confirm").performClick()
            compose.waitUntil(10_000) { File(firstTarget, first.name).isFile && first.exists() != moving &&
                app.graph.durableTransferRepository.list().any { it.plan.destinationPath == firstTarget.path && it.state.status == DurableTransferStatus.COMPLETED } }
            assertEquals("first owned copy", File(firstTarget, first.name).readText())
            cutAndOpenPaste(vm, source, secondTarget, second, moving)
            compose.onNodeWithTag("transfer_options_dialog").assertDoesNotExist()
            compose.waitUntil(10_000) { File(secondTarget, second.name).isFile && second.exists() != moving &&
                app.graph.durableTransferRepository.list().any { it.plan.destinationPath == secondTarget.path && it.state.status == DurableTransferStatus.COMPLETED } }
            assertEquals("second owned copy", File(secondTarget, second.name).readText())
            assertFalse(File(secondTarget, first.name).exists())
            val plans = app.graph.durableTransferRepository.list().filter { it.plan.destinationPath in listOf(firstTarget.path, secondTarget.path) }
            assertEquals(2, plans.size)
            assertEquals(2, plans.map { it.plan.id }.distinct().size)
            assertTrue(plans.all { it.plan.verification == TransferVerification.SHA256 && it.plan.move == moving })
            assertEquals("never selected", outside.readText())
            assertTrue(vm.transferPreferences.value.optionsFor(!moving).askBeforeTransfer)
        } finally {
            compose.runOnUiThread { vm.navigate(PanelId.LEFT, root.parentFile!!.path) }
            check(root.deleteRecursively())
        }
    }

    @Test fun cancelledDraftDoesNotSaveOrStartAndSettingsCanRestoreConfirmation() {
        val app = compose.activity.application as AFFileManagerApplication
        val root = File(app.getExternalFilesDir("validation"), "cancel-paste-${System.nanoTime()}").apply { check(mkdirs()) }
        val source = File(root, "source").apply { check(mkdir()) }
        val target = File(root, "target").apply { check(mkdir()) }
        val file = File(source, "leave-me.txt").apply { writeText("cancelled input") }
        try {
            cutAndOpenPaste(vm, source, target, file)
            compose.onNodeWithTag("transfer_options_sha256").performClick()
            compose.onNodeWithTag("transfer_options_ask").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(TransferOptions.defaults(true), vm.transferPreferences.value.optionsFor(true))
            assertEquals("cancelled input", file.readText())
            assertTrue(target.listFiles().orEmpty().isEmpty())
            compose.runOnUiThread { check(vm.saveTransferOptions(true, TransferOptions.defaults(true).copy(askBeforeTransfer = false))) }
            compose.onNodeWithText("More", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("settings_section_actions").performScrollTo().performClick()
            compose.onNodeWithTag("move_options_settings").performScrollTo().performClick()
            compose.onNodeWithTag("transfer_options_ask").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithTag("transfer_options_confirm").performClick()
            assertTrue(vm.transferPreferences.value.optionsFor(true).askBeforeTransfer)
            assertEquals("cancelled input", file.readText())
            assertTrue(target.listFiles().orEmpty().isEmpty())
        } finally {
            compose.runOnUiThread { vm.navigate(PanelId.LEFT, root.parentFile!!.path) }
            check(root.deleteRecursively())
        }
    }

    private fun cutAndOpenPaste(vm: MainViewModel, source: File, target: File, item: File, moving: Boolean = true) {
        compose.runOnUiThread { vm.activatePanel(PanelId.LEFT); vm.navigate(PanelId.LEFT, source.path) }
        compose.waitUntil(10_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.entries.any { it.absolutePath == item.path } }
        compose.runOnUiThread {
            vm.selectOnly(PanelId.LEFT, item.path)
            vm.copySelection(PanelId.LEFT, move = moving)
            vm.navigate(PanelId.LEFT, target.path)
        }
        compose.waitUntil(10_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.path == target.path }
        compose.onNodeWithContentDescription("Folder actions").performClick()
        compose.onNodeWithText("Paste", useUnmergedTree = true).performClick()
    }
}
