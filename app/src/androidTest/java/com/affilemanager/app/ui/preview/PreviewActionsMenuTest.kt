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
}
