package com.affilemanager.app.ui

data class ClipboardMergeResult<T>(
    val items: List<T>,
    val addedCount: Int,
    val duplicateCount: Int,
    val limitReached: Boolean,
)

object ClipboardMergeRules {
    fun <T> merge(
        existing: List<T>,
        additional: List<T>,
        maximum: Int,
        key: (T) -> String,
    ): ClipboardMergeResult<T> {
        require(maximum > 0) { "Clipboard limit must be positive" }
        val merged = ArrayList<T>(minOf(maximum, existing.size + additional.size))
        val keys = HashSet<String>(minOf(maximum, existing.size + additional.size))
        existing.forEach { item ->
            if (merged.size < maximum && keys.add(key(item))) merged += item
        }
        var added = 0
        var duplicates = 0
        var limitReached = existing.size > maximum
        additional.forEach { item ->
            val itemKey = key(item)
            when {
                itemKey in keys -> duplicates += 1
                merged.size >= maximum -> limitReached = true
                else -> {
                    keys += itemKey
                    merged += item
                    added += 1
                }
            }
        }
        return ClipboardMergeResult(merged, added, duplicates, limitReached)
    }
}
