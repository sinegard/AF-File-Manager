package com.affilemanager.app.ui.theme

/** Legacy fitted dialogs choose pan, which can put actions behind the IME. */
internal fun dialogDecorFitsSystemWindows(requested: Boolean, platformSdk: Int = android.os.Build.VERSION.SDK_INT): Boolean =
    requested && platformSdk >= 30
