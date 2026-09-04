package com.affilemanager.app.cleanup

import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import java.util.Locale

internal object OldMediaRules {
    const val OLD_AFTER_DAYS = 90L
    private const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

    fun isCategoryCandidate(entry: FileEntry): Boolean {
        val normalized = entry.absolutePath.replace('\\', '/').lowercase(Locale.ROOT)
        val screenshot = entry.name.contains("screenshot", ignoreCase = true) ||
            normalized.contains("/screenshots/") ||
            normalized.endsWith("/screenshots")
        return screenshot || entry.kind in setOf(EntryKind.IMAGE, EntryKind.VIDEO, EntryKind.AUDIO)
    }

    fun isOldCandidate(entry: FileEntry, nowMillis: Long): Boolean =
        entry.modifiedAtMillis > 0L &&
            nowMillis - entry.modifiedAtMillis >= OLD_AFTER_DAYS * DAY_MILLIS &&
            isCategoryCandidate(entry)
}
