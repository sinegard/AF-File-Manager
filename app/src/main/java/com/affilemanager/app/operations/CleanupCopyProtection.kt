package com.affilemanager.app.operations

import java.io.File

internal data class CleanupMoveSelection(val movable: List<File>, val retained: List<File>)

/** A whole selection must not be rejected because just one duplicate needs to stay. */
internal object CleanupCopyProtection {
    fun select(selected: List<File>, groups: List<List<String>>, keepOneCopy: Boolean = true): CleanupMoveSelection {
        require(selected.size <= 10_000) { "Per daug elementų" }
        // Only an explicit confirmation can opt out; automatic cleanup keeps one copy.
        if (!keepOneCopy) return CleanupMoveSelection(selected, emptyList())
        val selectedByPath = selected.associateBy { it.absoluteFile.toPath().normalize() }
        fun selectedRoot(path: String): File? {
            var current = File(path).absoluteFile.toPath().normalize()
            while (true) {
                selectedByPath[current]?.let { return it }
                current = current.parent ?: return null
            }
        }
        val retained = hashSetOf<File>()
        groups.forEach { group ->
            val roots = group.distinct().map(::selectedRoot)
            if (roots.isNotEmpty() && roots.all { it != null }) {
                selectedRoot(group.minOrNull()!!)?.let(retained::add)
            }
        }
        return CleanupMoveSelection(selected.filterNot(retained::contains), selected.filter(retained::contains))
    }
}
