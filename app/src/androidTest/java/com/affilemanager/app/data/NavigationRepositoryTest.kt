package com.affilemanager.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.SearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationRepositoryTest {
    @Test
    fun thumbnailModeIsOffByDefaultAndPersistsForOnlyTheSelectedDirectory() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val firstIdentity = "instrumentation:first:${System.nanoTime()}"
        val secondIdentity = "instrumentation:second:${System.nanoTime()}"
        val repository = NavigationRepository(application)
        try {
            assertFalse(repository.thumbnailsEnabled(firstIdentity))
            assertFalse(repository.thumbnailsEnabled(secondIdentity))

            repository.setThumbnailsEnabled(firstIdentity, true)

            assertTrue(NavigationRepository(application).thumbnailsEnabled(firstIdentity))
            assertFalse(NavigationRepository(application).thumbnailsEnabled(secondIdentity))
        } finally {
            repository.setThumbnailsEnabled(firstIdentity, false)
            repository.setThumbnailsEnabled(secondIdentity, false)
        }
    }

    @Test
    fun directoryDisplaySettingsPersistPerDirectoryAndCanBeCleared() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val firstIdentity = "instrumentation:display:first:${System.nanoTime()}"
        val secondIdentity = "instrumentation:display:second:${System.nanoTime()}"
        val repository = NavigationRepository(application)
        val settings = DirectoryDisplaySettings(
            layoutMode = DirectoryLayoutMode.GRID,
            iconScalePercent = 130,
            spacingScalePercent = 70,
            gridColumns = 5,
            showThumbnails = true,
        )
        try {
            assertEquals(null, repository.directoryDisplaySettings(firstIdentity))
            assertEquals(null, repository.directoryDisplaySettings(secondIdentity))

            repository.setDirectoryDisplaySettings(firstIdentity, settings)

            assertEquals(settings, NavigationRepository(application).directoryDisplaySettings(firstIdentity))
            assertEquals(null, NavigationRepository(application).directoryDisplaySettings(secondIdentity))

            repository.clearDirectoryDisplaySettings(firstIdentity)
            assertEquals(null, NavigationRepository(application).directoryDisplaySettings(firstIdentity))
        } finally {
            repository.clearDirectoryDisplaySettings(firstIdentity)
            repository.clearDirectoryDisplaySettings(secondIdentity)
        }
    }

    @Test
    fun savedSearchPersistsMultipleRootsAndAdvancedFilters() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val repository = NavigationRepository(application)
        val name = "instrumentation-search-${System.nanoTime()}"
        var savedId: String? = null
        try {
            val saved = repository.saveSearch(
                name = name,
                rootPaths = listOf(application.filesDir.absolutePath, application.cacheDir.absolutePath),
                filters = SearchFilters(
                    query = "report",
                    minBytes = 1_024,
                    maxBytes = 8_192,
                    modifiedAfter = 123_456L,
                    modifiedBefore = 987_654L,
                    kinds = setOf(EntryKind.DOCUMENT, EntryKind.IMAGE),
                    includeHidden = true,
                    useRegex = true,
                    tags = setOf("Projektas/Dokumentai", "Svarbu"),
                ),
            ).single { it.name == name }
            savedId = saved.id

            val restored = NavigationRepository(application).savedSearches().single { it.id == saved.id }
            assertEquals(2, restored.rootPaths.size)
            assertEquals(1_024L, restored.minBytes)
            assertEquals(8_192L, restored.maxBytes)
            assertEquals(123_456L, restored.modifiedAfter)
            assertEquals(987_654L, restored.modifiedBefore)
            assertEquals(setOf(EntryKind.DOCUMENT, EntryKind.IMAGE), restored.kinds)
            assertTrue(restored.includeHidden)
            assertTrue(restored.useRegex)
            assertEquals(setOf("Projektas/Dokumentai", "Svarbu"), restored.tags)
        } finally {
            savedId?.let(repository::removeSearch)
        }
    }
}
