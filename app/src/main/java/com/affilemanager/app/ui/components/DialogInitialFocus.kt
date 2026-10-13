package com.affilemanager.app.ui.components

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView

/** One-time non-editing focus for navigation forms, using their own native window. */
@Composable
internal fun rememberDialogInitialFocusTarget(): FocusRequester {
    val target = remember { FocusRequester() }
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(view) {
        var initialized = false
        val reset = Runnable {
            if (view.isAttachedToWindow && view.hasWindowFocus()) {
                target.requestFocus()
                keyboard?.hide()
            }
        }
        fun initialize() {
            if (!initialized && view.hasWindowFocus()) {
                initialized = true
                view.post(reset)
            }
        }
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { if (it) initialize() }
        observer.addOnWindowFocusChangeListener(listener)
        initialize()
        onDispose {
            view.removeCallbacks(reset)
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
        }
    }
    return target
}
