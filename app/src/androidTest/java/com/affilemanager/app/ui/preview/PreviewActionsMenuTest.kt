package com.affilemanager.app.ui.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PreviewActionsMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewUsesTheSameShareInfoAndDeleteVisibilityAsBrowsing() {
        val hidden = androidx.compose.runtime.mutableStateOf(com.affilemanager.app.data.FeatureVisibility())
        var shared = 0
        compose.setContent { MaterialTheme {
            androidx.compose.runtime.CompositionLocalProvider(com.affilemanager.app.ui.components.LocalFeatureVisibility provides hidden.value) {
                PreviewActionsMenu("all-sources", true, false, {}, null, null, { shared++ }, {}, onDetails = {}, onDelete = {})
            }
        } }
        compose.onNodeWithTag("preview_actions_menu").performClick()
        compose.runOnIdle { hidden.value = com.affilemanager.app.data.FeatureVisibility(setOf(
            com.affilemanager.app.data.OptionalFeature.SHARING, com.affilemanager.app.data.OptionalFeature.FILE_INFO,
            com.affilemanager.app.data.OptionalFeature.FILE_DELETE)) }
        compose.onNodeWithTag("preview_share_action").assertDoesNotExist()
        compose.onNodeWithTag("preview_details_action").assertDoesNotExist()
        compose.onNodeWithTag("preview_delete_action").assertDoesNotExist()
        compose.onNodeWithTag("open-with-action").assertIsDisplayed()
        compose.runOnIdle { hidden.value = com.affilemanager.app.data.FeatureVisibility() }
        compose.onNodeWithTag("preview_share_action").performClick()
        compose.runOnIdle { assertEquals(1, shared) }
    }

    @Test fun mediaActionsUseTheSameOverflowAndKeepTheirCallbacksAndBusyState() {
        val busy = mutableStateOf(false)
        val called = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            PreviewActionsMenu("remote-or-local-video", !busy.value, false, {}, null, null, {}, {},
                onTrim = { called += "trim" }, onExtractAudio = { called += "audio" }, onCompress = { called += "compress" })
        } }
        val tags = listOf("open_media_trim", "open_media_audio", "open_media_compress")
        tags.forEach { tag ->
            compose.onNodeWithTag(tag).assertDoesNotExist()
            compose.onNodeWithTag("preview_actions_menu").performClick()
            compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(listOf("trim", "audio", "compress"), called); busy.value = true }
        compose.onNodeWithTag("preview_actions_menu").performClick()
        tags.forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
        compose.runOnIdle { assertEquals(3, called.size) }
    }

    @Test fun actionsOnlyRunAfterChoosingAnItemAndMenuClosesEachTime() {
        val called = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            PreviewActionsMenu("local-or-remote", true, false,
                { called += "open" }, { called += "edit" }, { called += "sign" }, { called += "share" }, { called += "hash" })
        } }
        compose.onNodeWithTag("open-with-action").assertDoesNotExist()
        listOf("open-with-action", "edit-with-action", "sign-pdf-action", "preview_share_action", "preview_hash_action").forEach { tag ->
            compose.onNodeWithTag("preview_actions_menu").performClick()
            compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(listOf("open", "edit", "sign", "share", "hash"), called) }
    }

    @Test fun detailsIsAnExplicitOverflowActionAndNeverOpensOnItsOwn() {
        var opened = false
        compose.setContent { MaterialTheme {
            PreviewActionsMenu("image", true, false, {}, null, null, {}, {}, onDetails = { opened = true })
        } }
        compose.runOnIdle { assertEquals(false, opened) }
        compose.onNodeWithTag("preview_actions_menu").performClick()
        compose.onNodeWithTag("preview_details_action").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, opened) }
        compose.onNodeWithTag("preview_details_action").assertDoesNotExist()
    }

    @Test fun busyOrUnsupportedActionsCannotBeInvokedAndChangingFileClosesMenu() {
        val source = mutableStateOf("first")
        var actions = 0
        compose.setContent { MaterialTheme {
            PreviewActionsMenu(source.value, false, true, { actions++ }, null, { actions++ }, { actions++ }, { actions++ })
        } }
        compose.onNodeWithTag("preview_actions_menu").performClick()
        compose.onNodeWithTag("edit-with-action").assertDoesNotExist()
        compose.onNodeWithTag("sign-pdf-action").assertIsNotEnabled()
        compose.onNodeWithTag("preview_hash_action").assertIsNotEnabled()
        compose.runOnIdle { source.value = "second" }
        compose.onNodeWithTag("open-with-action").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun deleteIsAnExplicitPreviewAction() {
        var deleted = 0
        compose.setContent { MaterialTheme {
            PreviewActionsMenu(
                sourceKey = "deletable-file",
                editEnabled = true,
                hashRunning = false,
                onOpenWith = {},
                onEditWith = null,
                onSignPdf = null,
                onShare = {},
                onCalculateHash = {},
                onDelete = { deleted += 1 },
            )
        } }

        compose.onNodeWithTag("preview_actions_menu").performClick()
        compose.onNodeWithTag("preview_delete_action").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
        compose.onNodeWithTag("preview_delete_action").assertDoesNotExist()
    }
}
