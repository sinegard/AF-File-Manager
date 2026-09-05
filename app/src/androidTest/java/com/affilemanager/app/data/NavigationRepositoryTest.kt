package com.affilemanager.app.data

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.SearchFilters
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
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
        val inheritedDefaults = repository.directoryDisplayDefaults()?.settings
        val settings = DirectoryDisplaySettings(
            layoutMode = DirectoryLayoutMode.GRID,
            iconScalePercent = 130,
            spacingScalePercent = 70,
            gridColumns = 5,
            showThumbnails = true,
        )
        try {
            assertEquals(inheritedDefaults, repository.directoryDisplaySettings(firstIdentity))
            assertEquals(inheritedDefaults, repository.directoryDisplaySettings(secondIdentity))

            repository.setDirectoryDisplaySettings(firstIdentity, settings)

            assertEquals(settings, NavigationRepository(application).directoryDisplaySettings(firstIdentity))
            assertEquals(inheritedDefaults, NavigationRepository(application).directoryDisplaySettings(secondIdentity))

            repository.clearDirectoryDisplaySettings(firstIdentity)
            assertEquals(inheritedDefaults, NavigationRepository(application).directoryDisplaySettings(firstIdentity))
        } finally {
            repository.clearDirectoryDisplaySettings(firstIdentity)
            repository.clearDirectoryDisplaySettings(secondIdentity)
        }
    }

    @Test
    fun settingAnInheritedDefaultCreatesAnIndependentOverride() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val preferenceName = "navigation-independent-${System.nanoTime()}"
        val isolatedContext = object : ContextWrapper(application) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                application.getSharedPreferences(preferenceName, mode)
        }
        val identity = "instrumentation:display:independent:${System.nanoTime()}"
        val repository = NavigationRepository(isolatedContext)
        val inherited = DirectoryDisplaySettings(
            layoutMode = DirectoryLayoutMode.GRID,
            gridColumns = 4,
        )
        try {
            repository.setDirectoryDisplayDefaults(
                DirectoryDisplayDefaults(inherited, SortMode.NAME, SortDirection.ASCENDING),
            )
            assertEquals(inherited, repository.directoryDisplaySettings(identity))
            assertEquals(null, repository.directoryDisplaySettingsOverride(identity))
            assertEquals(
                DirectorySortSettings(SortMode.NAME, SortDirection.ASCENDING),
                repository.directorySortSettings(identity),
            )
            assertEquals(null, repository.directorySortSettingsOverride(identity))

            repository.setDirectoryDisplaySettings(identity, inherited)

            assertEquals(inherited, NavigationRepository(isolatedContext).directoryDisplaySettingsOverride(identity))
        } finally {
            application.deleteSharedPreferences(preferenceName)
        }
    }

    @Test
    fun sortSettingsPersistIndependentlyForEveryVirtualLocation() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val firstIdentity = "virtual:category/images-${System.nanoTime()}"
        val secondIdentity = "virtual:category/documents-${System.nanoTime()}"
        val repository = NavigationRepository(application)
        try {
            repository.setDirectorySortSettings(
                firstIdentity,
                DirectorySortSettings(SortMode.MODIFIED, SortDirection.DESCENDING),
            )
            repository.setDirectorySortSettings(
                secondIdentity,
                DirectorySortSettings(SortMode.SIZE, SortDirection.ASCENDING),
            )

            val restored = NavigationRepository(application)
            assertEquals(
                DirectorySortSettings(SortMode.MODIFIED, SortDirection.DESCENDING),
                restored.directorySortSettings(firstIdentity),
            )
            assertEquals(
                DirectorySortSettings(SortMode.MODIFIED, SortDirection.DESCENDING),
                restored.directorySortSettingsOverride(firstIdentity),
            )
            assertEquals(
                DirectorySortSettings(SortMode.SIZE, SortDirection.ASCENDING),
                restored.directorySortSettings(secondIdentity),
            )
            assertEquals(null, restored.directoryDisplaySettingsOverride(firstIdentity))
            assertEquals(null, restored.directorySortSettingsOverride("virtual:category:untouched-${System.nanoTime()}"))
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
