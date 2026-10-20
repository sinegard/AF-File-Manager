package com.affilemanager.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity

/** Re-request the whole input after a dialog's IME resize changes its scroll parents. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberKeyboardAwareInput(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, if (focused) imeBottom else 0) {
        if (focused && imeBottom > 0) requester.bringIntoView()
    }
    return Modifier.bringIntoViewRequester(requester).onFocusChanged { focused = it.hasFocus }
}
