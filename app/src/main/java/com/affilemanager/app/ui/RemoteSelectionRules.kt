package com.affilemanager.app.ui

/** Pure selection rules shared by remote-list state updates and unit tests. */
internal object RemoteSelectionRules {
    data class Result(
        val selectedPaths: Set<String>,
        val limitReached: Boolean = false,
    )

    fun toggle(
        current: Set<String>,
        availablePaths: Collection<String>,
        path: String,
        maximum: Int,
    ): Result {
        if (path !in availablePaths) return Result(current)
        if (path in current) return Result(current - path)
        if (current.size >= maximum) return Result(current, limitReached = true)
        return Result(current + path)
    }

    fun selectAll(availablePaths: List<String>, maximum: Int): Result = Result(
        selectedPaths = availablePaths.take(maximum).toSet(),
        limitReached = availablePaths.size > maximum,
    )

    fun set(
        current: Set<String>,
        availablePaths: Collection<String>,
        paths: Collection<String>,
        selected: Boolean,
        maximum: Int,
    ): Result {
        val available = availablePaths.toHashSet()
        val requested = paths.filterTo(linkedSetOf(), available::contains)
        if (!selected) return Result(current - requested)
        val capacity = (maximum - current.size).coerceAtLeast(0)
        val additions = requested.filterNot(current::contains)
        return Result(
            selectedPaths = current + additions.take(capacity),
            limitReached = additions.size > capacity,
        )
    }

    fun retainAvailable(current: Set<String>, availablePaths: Collection<String>): Set<String> =
        current.filterTo(linkedSetOf(), availablePaths::contains)
}
