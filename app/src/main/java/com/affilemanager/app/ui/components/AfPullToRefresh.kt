package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal object RefreshRequestRules {
    fun canStart(isRefreshing: Boolean): Boolean = !isRefreshing
}

/** A shared top-only refresh gesture for every AF browser surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "pull_to_refresh",
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { if (RefreshRequestRules.canStart(isRefreshing)) onRefresh() },
        modifier = modifier.testTag(testTag),
        content = content,
    )
}
