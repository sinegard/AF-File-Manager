package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun FileSizeBar(
    sizeBytes: Long,
    largestSizeBytes: Long,
    identity: String,
    modifier: Modifier = Modifier,
) {
    if (largestSizeBytes <= 0L) return
    val fraction = (sizeBytes.coerceAtLeast(0L).toDouble() / largestSizeBytes.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .testTag("file_size_bar_${identity.hashCode()}"),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        drawStopIndicator = {},
    )
}
