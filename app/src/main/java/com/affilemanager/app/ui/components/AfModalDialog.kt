package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

/**
 * AF's adaptive dialog structure. Short forms wrap their content while long lists and forms use
 * the available height, keeping actions predictable without imitating another product.
 */
@Composable
fun AfModalDialog(
    title: String,
    icon: ImageVector,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    translateTitle: Boolean = true,
    showFooter: Boolean = true,
    expandedContent: Boolean = false,
    actions: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Keep the dialog window fitted to its surface so Android can still dismiss
        // an outside tap; a full-screen wrapper consumes that tap as dialog content.
        BoxWithConstraints(modifier = Modifier.imePadding(), contentAlignment = Alignment.Center) {
            val maximumDialogHeight = maxHeight * 0.92f
            val compactHeight = maximumDialogHeight < 300.dp
            Surface(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth(0.94f)
                    .then(
                        if (expandedContent) Modifier.height(maximumDialogHeight)
                        else Modifier.heightIn(max = maximumDialogHeight),
                    )
                    .then(modifier),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                // A landscape keyboard can leave less room than the header and footer
                // together. Let the whole short dialog scroll instead of clipping actions.
                // Keep the content slot bounded so nested lists still receive finite height.
                Column(modifier = if (compactHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(icon, contentDescription = null)
                        Column(modifier = Modifier.weight(1f)) {
                            if (translateTitle) {
                                LText(
                                    title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            subtitle?.let {
                                LText(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = onDismissRequest) {
                            Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti"))
                        }
                    }
                    HorizontalDivider()
                    Box(
                        modifier = Modifier.fillMaxWidth().then(
                            if (compactHeight) Modifier.heightIn(max = 300.dp)
                            else Modifier.weight(1f, fill = expandedContent),
                        ),
                        content = content,
                    )
                    if (showFooter) {
                        HorizontalDivider()
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.End,
                            itemVerticalAlignment = Alignment.CenterVertically,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) { actions() }
                    }
                }
            }
        }
    }
}
