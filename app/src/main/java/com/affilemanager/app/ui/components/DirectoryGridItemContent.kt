package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Keeps every browser grid item on the same visual baseline regardless of name length. */
@Composable
fun DirectoryGridItemContent(
    title: String,
    metadata: String?,
    visualHeight: Dp,
    innerPadding: Dp,
    extraContentHeight: Dp = 0.dp,
    modifier: Modifier = Modifier,
    visual: @Composable BoxScope.() -> Unit,
    actions: @Composable BoxScope.() -> Unit,
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    val cardHeight = innerPadding * 2 +
        visualHeight +
        VISUAL_TEXT_GAP +
        TITLE_SLOT_HEIGHT +
        METADATA_SLOT_HEIGHT +
        extraContentHeight

    Box(modifier = modifier.fillMaxWidth().height(cardHeight)) {
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(visualHeight),
                contentAlignment = Alignment.Center,
                content = visual,
            )
            Spacer(Modifier.height(VISUAL_TEXT_GAP))
            Box(
                modifier = Modifier.fillMaxWidth().height(TITLE_SLOT_HEIGHT),
                contentAlignment = Alignment.TopStart,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(METADATA_SLOT_HEIGHT),
                contentAlignment = Alignment.TopStart,
            ) {
                if (metadata != null) {
                    Text(
                        text = metadata,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (extraContentHeight > 0.dp) {
                Column(modifier = Modifier.fillMaxWidth().height(extraContentHeight), content = extraContent)
            }
        }
        Box(modifier = Modifier.align(Alignment.TopEnd).zIndex(1f), content = actions)
    }
}

private val VISUAL_TEXT_GAP = 6.dp
private val TITLE_SLOT_HEIGHT = 40.dp
private val METADATA_SLOT_HEIGHT = 18.dp
