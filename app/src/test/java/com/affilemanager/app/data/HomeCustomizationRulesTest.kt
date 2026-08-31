package com.affilemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCustomizationRulesTest {
    private val defaults = listOf(
        HomeShortcut("downloads", "Downloads", "/storage/Downloads", builtIn = true),
        HomeShortcut("documents", "Documents", "/storage/Documents", builtIn = true),
    )

    @Test
    fun normalizationPreservesUserOrderAndAddsNewBuiltIns() {
        val saved = HomeCustomization(
            sectionOrder = listOf(HomeSection.QUICK_LOCATIONS),
            shortcuts = listOf(
                defaults[1].copy(visible = false),
                HomeShortcut("custom.one", "Project", "/storage/Project"),
            ),
        )

        val normalized = HomeCustomizationRules.normalize(saved, defaults)

        assertEquals(HomeSection.entries.toSet(), normalized.sectionOrder.toSet())
        assertEquals(listOf("documents", "custom.one", "downloads"), normalized.shortcuts.map(HomeShortcut::id))
        assertFalse(normalized.shortcuts.first().visible)
        assertTrue(normalized.shortcuts.last().builtIn)
    }

    @Test
    fun toolsAreOneHomeSectionInsteadOfSeparateProductSpecificPages() {
        val normalized = HomeCustomizationRules.normalize(HomeCustomization(), defaults)

        assertEquals(1, normalized.sectionOrder.count { it == HomeSection.TOOLS })
        assertTrue(HomeSection.TOOLS in normalized.sectionOrder)
    }

    @Test
    fun sectionsAndShortcutsCanBeReorderedWithoutLosingData() {
        val start = HomeCustomizationRules.normalize(HomeCustomization(), defaults)
        val sectionsMoved = HomeCustomizationRules.moveSection(start, HomeSection.QUICK_LOCATIONS, -2)
        val shortcutsMoved = HomeCustomizationRules.moveShortcut(sectionsMoved, "documents", -1)

        assertEquals(HomeSection.QUICK_LOCATIONS, shortcutsMoved.sectionOrder.first())
        assertEquals("documents", shortcutsMoved.shortcuts.first().id)
        assertEquals(defaults.map(HomeShortcut::id).toSet(), shortcutsMoved.shortcuts.map(HomeShortcut::id).toSet())
    }

    @Test
    fun builtInsCannotBeRemovedButCustomShortcutsCan() {
        val withCustom = HomeCustomizationRules.addShortcut(
            HomeCustomizationRules.normalize(HomeCustomization(), defaults),
            HomeShortcut("custom.one", "Project", "/storage/Project"),
        )
        val builtInRemoval = HomeCustomizationRules.removeShortcut(withCustom, "downloads")
        val customRemoval = HomeCustomizationRules.removeShortcut(builtInRemoval, "custom.one")

        assertTrue(customRemoval.shortcuts.any { it.id == "downloads" })
        assertFalse(customRemoval.shortcuts.any { it.id == "custom.one" })
    }

    @Test
    fun realQuickLocationFoldersUseStandardDirectoryNavigation() {
        val folderIds = listOf("builtin.downloads")

        folderIds.forEach { id ->
            assertEquals(null, HomeShortcutNavigationRules.categoryFor(id))
            assertFalse(HomeShortcutNavigationRules.isVirtualCategory(id))
        }
    }

    @Test
    fun mediaArchivesAndAppsUseDirectVirtualCategories() {
        assertEquals(FileCategory.DOCUMENTS, HomeShortcutNavigationRules.categoryFor("builtin.documents"))
        assertEquals(FileCategory.IMAGES, HomeShortcutNavigationRules.categoryFor("builtin.pictures"))
        assertEquals(FileCategory.VIDEOS, HomeShortcutNavigationRules.categoryFor("builtin.videos"))
        assertEquals(FileCategory.AUDIO, HomeShortcutNavigationRules.categoryFor("builtin.music"))
        assertEquals(FileCategory.ARCHIVES, HomeShortcutNavigationRules.categoryFor("builtin.archives"))
        assertEquals(FileCategory.APPS, HomeShortcutNavigationRules.categoryFor("builtin.apps"))
        assertEquals(FileCategory.INSTALLED_APPS, HomeShortcutNavigationRules.categoryFor("builtin.installed_apps"))
        assertTrue(HomeShortcutNavigationRules.isVirtualCategory("builtin.documents"))
        assertTrue(HomeShortcutNavigationRules.isVirtualCategory("builtin.archives"))
        assertTrue(HomeShortcutNavigationRules.isVirtualCategory("builtin.apps"))
        assertTrue(HomeShortcutNavigationRules.isVirtualCategory("builtin.installed_apps"))
    }
}
