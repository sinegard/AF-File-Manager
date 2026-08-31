package com.affilemanager.app.ui

internal enum class SystemBackAction {
    CLOSE_PREVIEW,
    CLOSE_HOME_TOOL_PAGE,
    SHOW_FILES,
    CLEAR_REMOTE_SELECTION,
    NAVIGATE_REMOTE_BACK,
    NAVIGATE_REMOTE_UP,
    CLEAR_SELECTION,
    NAVIGATE_BACK,
    NAVIGATE_UP,
    DEFER_TO_SYSTEM,
}

internal object BackNavigationRules {
    fun decide(
        previewOpen: Boolean,
        section: AppSection,
        filesHomeVisible: Boolean,
        selectedCount: Int,
        hasBackHistory: Boolean,
        hasParent: Boolean,
        homeToolPageOpen: Boolean = false,
        remoteSelectedCount: Int = 0,
        remoteConnected: Boolean = false,
        remoteHasBackHistory: Boolean = false,
        remoteHasParent: Boolean = false,
    ): SystemBackAction = when {
        previewOpen -> SystemBackAction.CLOSE_PREVIEW
        section == AppSection.FILES && homeToolPageOpen -> SystemBackAction.CLOSE_HOME_TOOL_PAGE
        section == AppSection.CONNECTIONS && remoteSelectedCount > 0 -> SystemBackAction.CLEAR_REMOTE_SELECTION
        section == AppSection.CONNECTIONS && remoteConnected && remoteHasBackHistory -> SystemBackAction.NAVIGATE_REMOTE_BACK
        section == AppSection.CONNECTIONS && remoteConnected && remoteHasParent -> SystemBackAction.NAVIGATE_REMOTE_UP
        section != AppSection.FILES -> SystemBackAction.SHOW_FILES
        filesHomeVisible -> SystemBackAction.DEFER_TO_SYSTEM
        selectedCount > 0 -> SystemBackAction.CLEAR_SELECTION
        hasBackHistory -> SystemBackAction.NAVIGATE_BACK
        hasParent -> SystemBackAction.NAVIGATE_UP
        else -> SystemBackAction.SHOW_FILES
    }
}

internal object SectionNavigationRules {
    fun shouldShowFilesHome(current: AppSection, requested: AppSection): Boolean =
        current == AppSection.FILES && requested == AppSection.FILES
}
