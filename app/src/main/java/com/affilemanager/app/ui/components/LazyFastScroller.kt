package com.affilemanager.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal object FastScrollMapping {
    fun targetIndex(pointerY: Float, trackHeight: Float, thumbHeight: Float, totalItems: Int, visibleItems: Int): Int {
        if (totalItems <= visibleItems || totalItems <= 1) return 0
        val travel = (trackHeight - thumbHeight).coerceAtLeast(1f)
        val fraction = ((pointerY - thumbHeight / 2f) / travel).coerceIn(0f, 1f)
        return (fraction * (totalItems - visibleItems)).roundToInt().coerceIn(0, totalItems - 1)
    }

    fun thumbFraction(firstVisibleItem: Int, totalItems: Int, visibleItems: Int): Float {
        val range = totalItems - visibleItems
        return if (range <= 0) 0f else firstVisibleItem.toFloat().div(range).coerceIn(0f, 1f)
    }
}

private data class FastScrollMetrics(val totalItems: Int, val visibleItems: Int, val firstVisibleItem: Int)

@Composable
internal fun BoxScope.LazyListFastScroller(state: LazyListState, modifier: Modifier = Modifier) {
    val metrics by remember(state) { derivedStateOf {
        FastScrollMetrics(state.layoutInfo.totalItemsCount, state.layoutInfo.visibleItemsInfo.size, state.firstVisibleItemIndex)
    } }
    FastScroller(metrics.totalItems, metrics.visibleItems, metrics.firstVisibleItem,
        state.isScrollInProgress, state::scrollToItem, modifier.testTag("list_fast_scroller"))
}

@Composable
internal fun BoxScope.LazyGridFastScroller(state: LazyGridState, modifier: Modifier = Modifier) {
    val metrics by remember(state) { derivedStateOf {
        FastScrollMetrics(state.layoutInfo.totalItemsCount, state.layoutInfo.visibleItemsInfo.size, state.firstVisibleItemIndex)
    } }
    FastScroller(metrics.totalItems, metrics.visibleItems, metrics.firstVisibleItem,
        state.isScrollInProgress, state::scrollToItem, modifier.testTag("grid_fast_scroller"))
}

@Composable
private fun BoxScope.FastScroller(
    totalItems: Int,
    visibleItems: Int,
    firstVisibleItem: Int,
    scrollInProgress: Boolean,
    onScrollTo: suspend (Int) -> Unit,
    modifier: Modifier,
) {
    if (visibleItems <= 0 || totalItems <= visibleItems + 2) return
    val scope = rememberCoroutineScope()
    val scrollingJob = remember { arrayOfNulls<Job>(1) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(scrollInProgress) {
        if (scrollInProgress) {
            visible = true
        } else {
            delay(1_500)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "fast-scroller-alpha")
    val visibleFraction = visibleItems.toFloat().div(totalItems).coerceIn(.08f, .45f)
    val thumbFraction = FastScrollMapping.thumbFraction(firstVisibleItem, totalItems, visibleItems)
    val interactionModifier = if (visible) {
        Modifier.pointerInput(totalItems, visibleItems) {
            val actualHeight = size.height.toFloat()
            val thumbHeight = (actualHeight * visibleFraction).coerceAtLeast(48.dp.toPx()).coerceAtMost(actualHeight)
            fun go(y: Float) {
                val target = FastScrollMapping.targetIndex(y, actualHeight, thumbHeight, totalItems, visibleItems)
                scrollingJob[0]?.cancel()
                scrollingJob[0] = scope.launch { onScrollTo(target) }
            }
            detectVerticalDragGestures(
                onDragStart = { offset -> go(offset.y) },
                onVerticalDrag = { change, _ ->
                    change.consume()
                    go(change.position.y)
                },
            )
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier.align(Alignment.CenterEnd).fillMaxHeight().width(32.dp).alpha(alpha)
            .then(interactionModifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.fillMaxHeight().width(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .10f)))
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxHeight().width(14.dp)) {
            val available = (maxHeight - maxHeight * visibleFraction).coerceAtLeast(0.dp)
            Box(Modifier.offset { IntOffset(0, (available.toPx() * thumbFraction).roundToInt()) }
                .height((maxHeight * visibleFraction).coerceAtLeast(48.dp)).width(8.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .72f), MaterialTheme.shapes.small)
                .align(Alignment.TopCenter))
        }
    }
}
