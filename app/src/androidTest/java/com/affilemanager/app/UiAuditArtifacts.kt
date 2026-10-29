package com.affilemanager.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Separate attempts must not overwrite each other's failure frames. */
internal fun uiAuditDirectory(context: Context): File {
    val attempt = InstrumentationRegistry.getArguments().getString("afAuditCapturePrefix") ?: "ui-audit"
    require(attempt.matches(Regex("[a-z0-9-]{1,80}")))
    return File(requireNotNull(context.getExternalFilesDir("validation")), "runs/$attempt").apply {
        check(isDirectory || mkdirs())
    }
}
