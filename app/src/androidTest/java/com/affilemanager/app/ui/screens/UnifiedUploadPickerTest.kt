package com.affilemanager.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.ui.MainViewModel
import org.junit.Rule
import org.junit.Test

class UnifiedUploadPickerTest {
    @get:Rule val compose = createComposeRule()
    @Test fun choosingStorageReplacesTheSendDialogAndCancelReturnsToIt() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = ViewModelStore()
        val vm = MainViewModel(app).also { store.put("picker", it) }
        try {
            compose.setContent { MaterialTheme { NearbySendDialog(vm, null, {}, {}) } }
            compose.onNodeWithTag("nearby_open_storage").performClick()
            compose.onNodeWithTag("local_upload_dialog").assertIsDisplayed()
            compose.onNodeWithTag("nearby_send_dialog").assertDoesNotExist()
            compose.onNodeWithContentDescription("Close").performClick()
            compose.onNodeWithTag("local_upload_dialog").assertDoesNotExist()
            compose.onNodeWithTag("nearby_send_dialog").assertIsDisplayed()
        } finally { compose.runOnUiThread { store.clear() } }
    }
}
