package com.affilemanager.app.editing

import com.affilemanager.app.workflow.AfWorkflowLimits

data class TextMergeConflict(
    val baseStartLine: Int,
    val baseText: String,
    val yourText: String,
    val currentText: String,
)

data class ThreeWayTextMergeResult(
    val text: String,
    val conflicts: List<TextMergeConflict>,
    val yourChangeCount: Int,
    val currentChangeCount: Int,
) {
    val clean: Boolean get() = conflicts.isEmpty()
}

/**
 * Conservative line-based diff3. Unique-line patience anchors keep the common
 * case fast; ambiguous repeated regions become visible conflicts instead of an
 * unsafe guessed merge.
 */
class ThreeWayTextMerge {
    fun merge(base: String, yours: String, current: String): ThreeWayTextMergeResult {
        require(base.length <= AfWorkflowLimits.MAX_TEXT_MERGE_CHARS) { "Original text is too large to merge" }
        require(yours.length <= AfWorkflowLimits.MAX_TEXT_MERGE_CHARS) { "Edited text is too large to merge" }
        require(current.length <= AfWorkflowLimits.MAX_TEXT_MERGE_CHARS) { "Current text is too large to merge" }
        if (yours == current) return ThreeWayTextMergeResult(yours, emptyList(), 0, 0)
        if (yours == base) return ThreeWayTextMergeResult(current, emptyList(), 0, 1)
        if (current == base) return ThreeWayTextMergeResult(yours, emptyList(), 1, 0)

        val baseLines = splitLines(base)
        val yourLines = splitLines(yours)
        val currentLines = splitLines(current)
        require(baseLines.size <= AfWorkflowLimits.MAX_TEXT_MERGE_LINES) { "Original text has too many lines to merge" }
        require(yourLines.size <= AfWorkflowLimits.MAX_TEXT_MERGE_LINES) { "Edited text has too many lines to merge" }
        require(currentLines.size <= AfWorkflowLimits.MAX_TEXT_MERGE_LINES) { "Current text has too many lines to merge" }

        val yourChanges = changes(baseLines, yourLines)
        val currentChanges = changes(baseLines, currentLines)
        val merged = ArrayList<String>(maxOf(yourLines.size, currentLines.size))
        val conflicts = ArrayList<TextMergeConflict>()
        var yourIndex = 0
        var currentIndex = 0
        var baseCursor = 0

        while (yourIndex < yourChanges.size || currentIndex < currentChanges.size) {
            val nextYour = yourChanges.getOrNull(yourIndex)
            val nextCurrent = currentChanges.getOrNull(currentIndex)
            val clusterStart = minOf(nextYour?.start ?: Int.MAX_VALUE, nextCurrent?.start ?: Int.MAX_VALUE)
            merged += baseLines.subList(baseCursor, clusterStart)

            var clusterEnd = maxOf(
                nextYour?.takeIf { it.start == clusterStart }?.end ?: clusterStart,
                nextCurrent?.takeIf { it.start == clusterStart }?.end ?: clusterStart,
            )
            val yourCluster = ArrayList<LineChange>()
            val currentCluster = ArrayList<LineChange>()
            var expanded: Boolean
            do {
                expanded = false
                while (yourIndex < yourChanges.size && belongsToCluster(yourChanges[yourIndex], clusterStart, clusterEnd)) {
                    val change = yourChanges[yourIndex++]
                    yourCluster += change
                    if (change.end > clusterEnd) {
                        clusterEnd = change.end
                        expanded = true
                    }
                }
                while (currentIndex < currentChanges.size && belongsToCluster(currentChanges[currentIndex], clusterStart, clusterEnd)) {
                    val change = currentChanges[currentIndex++]
                    currentCluster += change
                    if (change.end > clusterEnd) {
                        clusterEnd = change.end
                        expanded = true
                    }
                }
            } while (expanded)

            val baseSegment = baseLines.subList(clusterStart, clusterEnd)
            val yourSegment = applyChanges(baseLines, clusterStart, clusterEnd, yourCluster)
            val currentSegment = applyChanges(baseLines, clusterStart, clusterEnd, currentCluster)
            when {
                yourCluster.isEmpty() || yourSegment == baseSegment -> merged += currentSegment
                currentCluster.isEmpty() || currentSegment == baseSegment -> merged += yourSegment
                yourSegment == currentSegment -> merged += yourSegment
                else -> {
                    conflicts += TextMergeConflict(
                        baseStartLine = clusterStart + 1,
                        baseText = joinLines(baseSegment),
                        yourText = joinLines(yourSegment),
                        currentText = joinLines(currentSegment),
                    )
                    merged += conflictMarkerLines(yourSegment, baseSegment, currentSegment)
                }
            }
            baseCursor = clusterEnd
        }
        merged += baseLines.subList(baseCursor, baseLines.size)
        return ThreeWayTextMergeResult(
            text = joinLines(merged),
            conflicts = conflicts,
            yourChangeCount = yourChanges.size,
            currentChangeCount = currentChanges.size,
        )
    }

    private data class LineChange(val start: Int, val end: Int, val replacement: List<String>)

    private fun changes(base: List<String>, variant: List<String>): List<LineChange> {
        val matches = ArrayList<Pair<Int, Int>>()
        collectMatches(base, variant, 0, base.size, 0, variant.size, matches)
        val ordered = matches.distinct().sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        val result = ArrayList<LineChange>()
        var baseCursor = 0
        var variantCursor = 0
        ordered.forEach { (baseIndex, variantIndex) ->
            if (baseIndex < baseCursor || variantIndex < variantCursor) return@forEach
            if (baseIndex > baseCursor || variantIndex > variantCursor) {
                result += LineChange(
                    start = baseCursor,
                    end = baseIndex,
                    replacement = variant.subList(variantCursor, variantIndex).toList(),
                )
            }
            baseCursor = baseIndex + 1
            variantCursor = variantIndex + 1
        }
        if (baseCursor < base.size || variantCursor < variant.size) {
            result += LineChange(baseCursor, base.size, variant.subList(variantCursor, variant.size).toList())
        }
        return result
    }

    private fun collectMatches(
        base: List<String>,
        variant: List<String>,
        initialBaseStart: Int,
        initialBaseEnd: Int,
        initialVariantStart: Int,
        initialVariantEnd: Int,
        output: MutableList<Pair<Int, Int>>,
    ) {
        var baseStart = initialBaseStart
        var baseEnd = initialBaseEnd
        var variantStart = initialVariantStart
        var variantEnd = initialVariantEnd
        while (baseStart < baseEnd && variantStart < variantEnd && base[baseStart] == variant[variantStart]) {
            output += baseStart to variantStart
            baseStart += 1
            variantStart += 1
        }
        val suffix = ArrayList<Pair<Int, Int>>()
        while (baseStart < baseEnd && variantStart < variantEnd && base[baseEnd - 1] == variant[variantEnd - 1]) {
            baseEnd -= 1
            variantEnd -= 1
            suffix += baseEnd to variantEnd
        }
        if (baseStart >= baseEnd || variantStart >= variantEnd) {
            output += suffix.asReversed()
            return
        }

        val baseUnique = uniquePositions(base, baseStart, baseEnd)
        val variantUnique = uniquePositions(variant, variantStart, variantEnd)
        val candidates = baseUnique.mapNotNull { (line, baseIndex) ->
            variantUnique[line]?.let { variantIndex -> baseIndex to variantIndex }
        }.sortedBy { it.first }
        val anchors = longestIncreasingBySecond(candidates)
        if (anchors.isEmpty()) {
            output += suffix.asReversed()
            return
        }

        var previousBase = baseStart
        var previousVariant = variantStart
        anchors.forEach { (baseIndex, variantIndex) ->
            collectMatches(base, variant, previousBase, baseIndex, previousVariant, variantIndex, output)
            output += baseIndex to variantIndex
            previousBase = baseIndex + 1
            previousVariant = variantIndex + 1
        }
        collectMatches(base, variant, previousBase, baseEnd, previousVariant, variantEnd, output)
        output += suffix.asReversed()
    }

    private fun uniquePositions(lines: List<String>, start: Int, end: Int): Map<String, Int> {
        val positions = HashMap<String, Int>()
        val repeated = HashSet<String>()
        for (index in start until end) {
            val line = lines[index]
            if (positions.putIfAbsent(line, index) != null) repeated += line
        }
        repeated.forEach(positions::remove)
        return positions
    }

    private fun longestIncreasingBySecond(values: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        if (values.isEmpty()) return emptyList()
        val tails = IntArray(values.size)
        val previous = IntArray(values.size) { -1 }
        var length = 0
        values.indices.forEach { index ->
            val value = values[index].second
            var low = 0
            var high = length
            while (low < high) {
                val middle = (low + high) ushr 1
                if (values[tails[middle]].second < value) low = middle + 1 else high = middle
            }
            if (low > 0) previous[index] = tails[low - 1]
            tails[low] = index
            if (low == length) length += 1
        }
        val result = ArrayList<Pair<Int, Int>>(length)
        var cursor = tails[length - 1]
        while (cursor >= 0) {
            result += values[cursor]
            cursor = previous[cursor]
        }
        result.reverse()
        return result
    }

    private fun belongsToCluster(change: LineChange, start: Int, end: Int): Boolean {
        if (end == start) return change.start == start
        // A change beginning exactly where another range ends is adjacent, not
        // overlapping. Insertions inside an affected range still belong to it.
        return change.start < end
    }

    private fun applyChanges(
        base: List<String>,
        start: Int,
        end: Int,
        changes: List<LineChange>,
    ): List<String> {
        if (changes.isEmpty()) return base.subList(start, end).toList()
        val result = ArrayList<String>()
        var cursor = start
        changes.sortedBy(LineChange::start).forEach { change ->
            if (change.start > cursor) result += base.subList(cursor, change.start)
            result += change.replacement
            cursor = maxOf(cursor, change.end)
        }
        if (cursor < end) result += base.subList(cursor, end)
        return result
    }

    private fun conflictMarkerLines(
        yours: List<String>,
        base: List<String>,
        current: List<String>,
    ): List<String> = buildList {
        add("<<<<<<< YOUR CHANGES\n")
        addAll(ensureTerminated(yours))
        add("||||||| ORIGINAL\n")
        addAll(ensureTerminated(base))
        add("=======\n")
        addAll(ensureTerminated(current))
        add(">>>>>>> CURRENT FILE\n")
    }

    private fun ensureTerminated(lines: List<String>): List<String> = when {
        lines.isEmpty() -> emptyList()
        lines.last().endsWith('\n') -> lines
        else -> lines.dropLast(1) + (lines.last() + "\n")
    }

    private fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        var start = 0
        text.forEachIndexed { index, character ->
            if (character == '\n') {
                result += text.substring(start, index + 1)
                start = index + 1
            }
        }
        if (start < text.length) result += text.substring(start)
        return result
    }

    private fun joinLines(lines: List<String>): String = buildString(lines.sumOf(String::length)) {
        lines.forEach(::append)
    }
}
