package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.affilemanager.app.model.StorageRoot
import com.affilemanager.app.model.StorageRootKind
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StorageOverviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val internal = StorageRoot("primary", "Internal", "/storage/emulated/0", 1_000L, 400L, false, StorageRootKind.INTERNAL)
    private val usb = StorageRoot("usb", "USB fixture", "/storage/AF_USB", 2_000L, 1_000L, true, StorageRootKind.USB_STORAGE)

    @Test fun mountedVolumesAppearAndDisappearTogetherWithTheAllStorageAction() {
        val mounted = mutableStateOf(listOf(internal))
        compose.setContent { MaterialTheme {
            StorageOverviewCard(internal, mounted.value, null, emptyList(), false, false, null, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("storage_usage_primary").assertIsDisplayed()
        compose.onNodeWithText("60%").assertIsDisplayed()
        compose.onNodeWithTag("storage_usage_usb").assertDoesNotExist()
        compose.onNodeWithTag("analyze_all_storage").assertDoesNotExist()
        compose.runOnIdle { mounted.value = listOf(internal, usb) }
        compose.onNodeWithTag("storage_usage_usb").assertIsDisplayed()
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithTag("analyze_all_storage").assertIsDisplayed()
        compose.runOnIdle { mounted.value = listOf(internal) }
        compose.onNodeWithTag("storage_usage_usb").assertDoesNotExist()
        compose.onNodeWithTag("analyze_storage_usb").assertDoesNotExist()
        compose.onNodeWithTag("analyze_all_storage").assertDoesNotExist()
    }

    @Test fun longVolumeNameAndLargeRtlTextKeepUsageInsideANarrowRow() {
        val long = usb.copy(title = "A removable USB volume with a very long name that wraps")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme { Box(Modifier.width(280.dp)) {
                    StorageOverviewCard(long, listOf(long), null, emptyList(), false, false, null, {}, {}, {}, {}, {})
                } }
            }
        }
        val row = compose.onNodeWithTag("storage_usage_usb").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val percentage = compose.onNodeWithText("50%").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(percentage.left >= row.left && percentage.right <= row.right)
        val name = compose.onNodeWithText(long.title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(name.left >= row.left && name.right <= row.right)
        assertTrue(name.right <= percentage.left || percentage.right <= name.left)
    }
}
