package com.affilemanager.app.ui

import com.affilemanager.app.core.FileSystemRules
import java.io.File

/**
 * Removes successfully moved roots from the current analysis snapshot without rescanning storage.
 * Aggregate scan totals intentionally remain a snapshot of the last full analysis.
 */
internal object AnalysisResultPruner {
    fun prune(state: AnalysisUiState, removedPaths: Collection<String>): AnalysisUiState {
        val removedRoots = removedPaths.asSequence()
            .mapNotNull { path -> runCatching { File(path).canonicalFile }.getOrNull() }
            .distinctBy(File::getAbsolutePath)
            .toList()
        if (removedRoots.isEmpty()) return state

        fun removed(path: String): Boolean = runCatching {
            val candidate = File(path).canonicalFile
            removedRoots.any { root -> FileSystemRules.isContained(root, candidate) }
        }.getOrDefault(false)

        val analysis = state.analysis?.let { current ->
            current.copy(
                largestFiles = current.largestFiles.filterNot { removed(it.absolutePath) },
                oldestFiles = current.oldestFiles.filterNot { removed(it.absolutePath) },
                emptyDirectories = current.emptyDirectories.filterNot(::removed),
                largestDirectories = current.largestDirectories.filterNot { removed(it.path) },
                installerAndArchiveFiles = current.installerAndArchiveFiles.filterNot { removed(it.absolutePath) },
                similarImageCandidates = current.similarImageCandidates.filterNot { removed(it.absolutePath) },
                oldMediaFiles = current.oldMediaFiles.filterNot { removed(it.absolutePath) },
            )
        }
        val duplicates = state.duplicates.mapNotNull { group ->
            group.copy(paths = group.paths.filterNot(::removed)).takeIf { it.paths.size >= 2 }
        }
        val similarImages = state.similarImages.mapNotNull { group ->
            group.copy(files = group.files.filterNot { removed(it.absolutePath) }).takeIf { it.files.size >= 2 }
        }
        return state.copy(
            analysis = analysis,
            duplicates = duplicates,
            similarImages = similarImages,
        )
    }
}
