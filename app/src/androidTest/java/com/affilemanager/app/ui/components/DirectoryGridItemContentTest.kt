package com.affilemanager.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DirectoryGridItemContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun longTitleKeepsCardVisualAndActionsOnTheSameBaselines() {
        compose.setContent {
            MaterialTheme {
                Row {
                    DirectoryGridItemContent(
                        title = "test",
                        metadata = null,
                        visualHeight = 76.dp,
                        innerPadding = 9.dp,
                        modifier = Modifier.weight(1f).testTag("short_card"),
                        visual = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Green)
                                    .testTag("short_visual"),
                            )
                        },
                        actions = {
                            Box(Modifier.size(48.dp).testTag("short_actions"))
                        },
                    )
                    DirectoryGridItemContent(
                        title = "A very long file name that wraps onto another line",
                        metadata = "72.4 KB",
                        visualHeight = 76.dp,
                        innerPadding = 9.dp,
                        modifier = Modifier.weight(1f).testTag("long_card"),
                        visual = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(Color.Blue)
                                    .testTag("long_visual"),
                            )
                        },
                        actions = {
                            Box(Modifier.size(48.dp).testTag("long_actions"))
                        },
                    )
                }
            }
        }

        compose.waitForIdle()
        val shortCard = compose.onNodeWithTag("short_card").fetchSemanticsNode().boundsInRoot
        val longCard = compose.onNodeWithTag("long_card").fetchSemanticsNode().boundsInRoot
        val shortVisual = compose.onNodeWithTag("short_visual").fetchSemanticsNode().boundsInRoot
        val longVisual = compose.onNodeWithTag("long_visual").fetchSemanticsNode().boundsInRoot
        val shortActions = compose.onNodeWithTag("short_actions").fetchSemanticsNode().boundsInRoot
        val longActions = compose.onNodeWithTag("long_actions").fetchSemanticsNode().boundsInRoot

        assertEquals(shortCard.height, longCard.height, 0.5f)
        assertEquals(shortVisual.top, longVisual.top, 0.5f)
        assertEquals(shortActions.top, longActions.top, 0.5f)
    }
}
