package com.affilemanager.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BackNavigationRulesTest {
    @Test
    fun actionsFollowUserVisiblePriority() {
        assertEquals(
            SystemBackAction.CLOSE_PREVIEW,
            BackNavigationRules.decide(true, AppSection.ANALYZE, false, 1, true, true),
        )
        assertEquals(
            SystemBackAction.CLOSE_HOME_TOOL_PAGE,
            BackNavigationRules.decide(
                previewOpen = false,
                section = AppSection.FILES,
                filesHomeVisible = true,
                homeToolPageOpen = true,
                selectedCount = 0,
                hasBackHistory = false,
                hasParent = false,
            ),
        )
        assertEquals(
            SystemBackAction.CLEAR_REMOTE_SELECTION,
            BackNavigationRules.decide(false, AppSection.CONNECTIONS, false, 1, true, true, remoteSelectedCount = 2),
        )
        assertEquals(
            SystemBackAction.SHOW_FILES,
            BackNavigationRules.decide(false, AppSection.CONNECTIONS, false, 1, true, true, remoteSelectedCount = 0),
        )
        assertEquals(
            SystemBackAction.NAVIGATE_REMOTE_BACK,
            BackNavigationRules.decide(
                false,
                AppSection.CONNECTIONS,
                false,
                0,
                false,
                false,
                remoteConnected = true,
                remoteHasBackHistory = true,
                remoteHasParent = true,
            ),
        )
        assertEquals(
            SystemBackAction.NAVIGATE_REMOTE_UP,
            BackNavigationRules.decide(
                false,
                AppSection.CONNECTIONS,
                false,
                0,
                false,
                false,
                remoteConnected = true,
                remoteHasBackHistory = false,
                remoteHasParent = true,
            ),
        )
        assertEquals(
            SystemBackAction.DEFER_TO_SYSTEM,
            BackNavigationRules.decide(false, AppSection.FILES, true, 1, true, true),
        )
        assertEquals(
            SystemBackAction.CLEAR_SELECTION,
            BackNavigationRules.decide(false, AppSection.FILES, false, 1, true, true),
        )
        assertEquals(
            SystemBackAction.NAVIGATE_BACK,
            BackNavigationRules.decide(false, AppSection.FILES, false, 0, true, true),
        )
        assertEquals(
            SystemBackAction.NAVIGATE_UP,
            BackNavigationRules.decide(false, AppSection.FILES, false, 0, false, true),
        )
        assertEquals(
            SystemBackAction.SHOW_FILES,
            BackNavigationRules.decide(false, AppSection.FILES, false, 0, false, false),
        )
    }

    @Test
    fun filesHomeOnlyOpensWhenTheAlreadySelectedDestinationIsTappedAgain() {
        assertEquals(false, SectionNavigationRules.shouldShowFilesHome(AppSection.ANALYZE, AppSection.FILES))
        assertEquals(false, SectionNavigationRules.shouldShowFilesHome(AppSection.FILES, AppSection.ANALYZE))
        assertEquals(true, SectionNavigationRules.shouldShowFilesHome(AppSection.FILES, AppSection.FILES))
    }
}
