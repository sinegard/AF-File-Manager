package com.affilemanager.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class DragSelectionChange(
    val indices: IntRange,
    val selected: Boolean,
)

/**
 * Keeps one selection direction for the whole gesture. Starting on an
 * unselected item selects every crossed item; starting on a selected item
 * deselects them. Re-entering an item never toggles it a second time.
 */
internal class DragSelectionTracker(
    private val isSelected: (Int) -> Boolean,
) {
    private var lastIndex: Int? = null
    private var selecting = true

    fun start(index: Int): DragSelectionChange {
        selecting = !isSelected(index)
        lastIndex = index
        return DragSelectionChange(index..index, selecting)
    }

    fun moveTo(index: Int): DragSelectionChange? {
        val previous = lastIndex ?: return start(index)
        if (previous == index) return null
        lastIndex = index
        return DragSelectionChange(
            indices = minOf(previous, index)..maxOf(previous, index),
            selected = selecting,
        )
    }
}

@Composable
internal fun Modifier.longPressDragSelect(
    state: LazyListState,
    itemCount: Int,
    isSelected: (Int) -> Boolean,
    onSelectionChange: (IntRange, Boolean) -> Unit,
): Modifier {
    val selectedState = rememberUpdatedState(isSelected)
    val changeState = rememberUpdatedState(onSelectionChange)
    val density = LocalDensity.current
    val edgeSizePx = with(density) { 48.dp.toPx() }
    val scrollStepPx = with(density) { 14.dp.toPx() }
    return pointerInput(state, itemCount, edgeSizePx, scrollStepPx) {
        detectRangeSelectionDrag(
            itemCount = itemCount,
            edgeSizePx = edgeSizePx,
            scrollStepPx = scrollStepPx,
            indexAt = { position ->
                state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    position.y >= item.offset && position.y < item.offset + item.size
                }?.index
            },
            isSelected = { index -> selectedState.value(index) },
            onSelectionChange = { indices, selected -> changeState.value(indices, selected) },
            scrollBy = state::scrollBy,
        )
    }
}

@Composable
internal fun Modifier.longPressDragSelect(
    state: LazyGridState,
    itemCount: Int,
    isSelected: (Int) -> Boolean,
    onSelectionChange: (IntRange, Boolean) -> Unit,
): Modifier {
    val selectedState = rememberUpdatedState(isSelected)
    val changeState = rememberUpdatedState(onSelectionChange)
    val density = LocalDensity.current
    val edgeSizePx = with(density) { 48.dp.toPx() }
    val scrollStepPx = with(density) { 14.dp.toPx() }
    return pointerInput(state, itemCount, edgeSizePx, scrollStepPx) {
        detectRangeSelectionDrag(
            itemCount = itemCount,
            edgeSizePx = edgeSizePx,
            scrollStepPx = scrollStepPx,
            indexAt = { position ->
                state.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                    position.x >= item.offset.x && position.x < item.offset.x + item.size.width &&
                        position.y >= item.offset.y && position.y < item.offset.y + item.size.height
                }?.index
            },
            isSelected = { index -> selectedState.value(index) },
            onSelectionChange = { indices, selected -> changeState.value(indices, selected) },
            scrollBy = state::scrollBy,
        )
    }
}

private suspend fun PointerInputScope.detectRangeSelectionDrag(
    itemCount: Int,
    edgeSizePx: Float,
    scrollStepPx: Float,
    indexAt: (Offset) -> Int?,
    isSelected: (Int) -> Boolean,
    onSelectionChange: (IntRange, Boolean) -> Unit,
    scrollBy: suspend (Float) -> Float,
) = coroutineScope {
    var tracker: DragSelectionTracker? = null
    var latestPosition: Offset? = null
    var autoScrollDirection = 0
    var autoScrollJob: Job? = null

    fun applyAt(position: Offset) {
        val index = indexAt(position)?.takeIf { it in 0 until itemCount } ?: return
        val activeTracker = tracker
        val change = if (activeTracker == null) {
            DragSelectionTracker(isSelected).also { tracker = it }.start(index)
        } else {
            activeTracker.moveTo(index) ?: return
        }
        onSelectionChange(change.indices, change.selected)
    }

    fun stopAutoScroll() {
        autoScrollDirection = 0
        autoScrollJob?.cancel()
        autoScrollJob = null
    }

    fun updateAutoScroll(position: Offset) {
        val direction = when {
            position.y < edgeSizePx -> -1
            position.y > size.height - edgeSizePx -> 1
            else -> 0
        }
        if (direction == 0) {
            stopAutoScroll()
            return
        }
        if (direction == autoScrollDirection && autoScrollJob?.isActive == true) return
        stopAutoScroll()
        autoScrollDirection = direction
        autoScrollJob = launch {
            while (isActive && autoScrollDirection == direction) {
                val consumed = scrollBy(direction * scrollStepPx)
                if (consumed != 0f) latestPosition?.let(::applyAt)
                delay(16)
            }
        }
    }

    fun finishGesture() {
        stopAutoScroll()
        latestPosition = null
        tracker = null
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var recognitionChange: PointerInputChange? = null
        val cancelledBeforeLongPress: Boolean = withTimeoutOrNull<Boolean>(viewConfiguration.longPressTimeoutMillis) {
            var cancelled: Boolean? = null
            while (cancelled == null) {
                val change = awaitPointerEvent(PointerEventPass.Initial)
                    .changes
                    .firstOrNull { it.id == down.id }
                    ?: return@withTimeoutOrNull true

                // Instrumented gestures can advance the pointer event clock
                // without waiting the same amount of wall-clock time. More
                // importantly, this also preserves a real user's first move
                // when it arrives just after the hold threshold.
                if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                    recognitionChange = change
                    cancelled = false
                } else if (!change.pressed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    cancelled = true
                }
            }
            cancelled
        } ?: false
        if (cancelledBeforeLongPress == true) return@awaitEachGesture
        try {
            // Select immediately when the hold is recognized. Waiting for the
            // first move would make a stationary long press do nothing and can
            // lose the item where a fast drag started.
            latestPosition = down.position
            applyAt(down.position)
            updateAutoScroll(down.position)

            recognitionChange?.let { change ->
                change.consume()
                latestPosition = change.position
                applyAt(change.position)
                updateAutoScroll(change.position)
            }

            while (recognitionChange?.pressed != false) {
                val change: PointerInputChange = awaitPointerEvent(PointerEventPass.Initial)
                    .changes
                    .firstOrNull { it.id == down.id }
                    ?: break
                change.consume()
                latestPosition = change.position
                applyAt(change.position)
                updateAutoScroll(change.position)
                if (!change.pressed) break
            }
        } finally {
            finishGesture()
        }
    }
}
