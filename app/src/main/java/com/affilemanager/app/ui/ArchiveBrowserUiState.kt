package com.affilemanager.app.ui

import com.affilemanager.app.data.DirectoryDisplayDefaults
import com.affilemanager.app.ui.components.DirectoryEntryFilter
import java.util.UUID

/** One live archive owns this state even while an extracted child is previewed. */
data class ArchiveBrowserUiState(
    val id: String = UUID.randomUUID().toString(),
    val path: String = "",
    val display: DirectoryDisplayDefaults? = null,
    val searchVisible: Boolean = false,
    val query: String = "",
    val filters: Set<DirectoryEntryFilter> = emptySet(),
)
