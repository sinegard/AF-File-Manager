package com.affilemanager.app.ui.components

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.affilemanager.app.data.OptionalFeature

/** The item menu and selection toolbar use identical, stable visibility keys. */
internal enum class BrowserAction(val feature: OptionalFeature) {
    COPY(OptionalFeature.FILE_COPY), MOVE(OptionalFeature.FILE_MOVE), RENAME(OptionalFeature.FILE_RENAME),
    ARCHIVE(OptionalFeature.FILE_ARCHIVE), INFO(OptionalFeature.FILE_INFO), DELETE(OptionalFeature.FILE_DELETE),
    BOOKMARK(OptionalFeature.FILE_BOOKMARK), SHARE(OptionalFeature.SHARING), FAVORITE(OptionalFeature.FAVORITES),
}

@Composable
internal fun BrowserActionIconButton(action: BrowserAction, onClick: () -> Unit, modifier: Modifier = Modifier,
                                     enabled: Boolean = true, content: @Composable () -> Unit) {
    if (featureVisible(action.feature)) IconButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
}

@Composable
internal fun BrowserActionMenuItem(action: BrowserAction, text: @Composable () -> Unit, onClick: () -> Unit,
                                  modifier: Modifier = Modifier, leadingIcon: (@Composable () -> Unit)? = null,
                                  enabled: Boolean = true) {
    if (featureVisible(action.feature)) DropdownMenuItem(text = text, onClick = onClick, modifier = modifier,
        leadingIcon = leadingIcon, enabled = enabled)
}
