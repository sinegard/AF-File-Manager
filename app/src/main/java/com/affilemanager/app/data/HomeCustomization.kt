package com.affilemanager.app.data

enum class HomeSection {
    STORAGE,
    TOOLS,
    QUICK_LOCATIONS,
    RECENT_FILES,
}

/**
 * Display settings are deliberately scoped to the surface the user is looking at. Changing the
 * storage cards must not silently change Quick locations, Favorites, or Tags.
 */
enum class HomeDisplayArea {
    STORAGE,
    QUICK_LOCATIONS,
    FAVORITES,
    TAGS,
}

data class HomeShortcut(
    val id: String,
    val title: String,
    val path: String,
    val visible: Boolean = true,
    val builtIn: Boolean = false,
    val section: HomeSection = HomeSection.QUICK_LOCATIONS,
)

data class HomeCustomization(
    val sectionOrder: List<HomeSection> = HomeSection.entries,
    val shortcuts: List<HomeShortcut> = emptyList(),
    val storageOrder: List<String> = emptyList(),
    val hiddenStorageIds: Set<String> = emptySet(),
    val hiddenSections: Set<HomeSection> = emptySet(),
    val toolOrder: List<String> = emptyList(),
    val hiddenToolIds: Set<String> = emptySet(),
)

/**
 * Quick locations that point at real directories must use the normal local
 * browser. Only shortcuts without a concrete directory are virtual categories.
 */
object HomeShortcutNavigationRules {
    private val virtualCategories = mapOf(
        "builtin.documents" to FileCategory.DOCUMENTS,
        "builtin.pictures" to FileCategory.IMAGES,
        "builtin.videos" to FileCategory.VIDEOS,
        "builtin.music" to FileCategory.AUDIO,
        "builtin.archives" to FileCategory.ARCHIVES,
        "builtin.apps" to FileCategory.APPS,
        "builtin.installed_apps" to FileCategory.INSTALLED_APPS,
    )

    fun categoryFor(shortcutId: String): FileCategory? = virtualCategories[shortcutId]

    fun isVirtualCategory(shortcutId: String): Boolean = shortcutId in virtualCategories
}

object HomeCustomizationRules {
    const val MAX_SHORTCUTS = 32
    const val MAX_TITLE_LENGTH = 80
    const val MAX_PATH_LENGTH = 4_096
    const val MAX_ID_LENGTH = 120
    const val ROOT_STORAGE_ID = "af.root"
    val DEFAULT_TOOL_IDS = listOf("trash", "plans", "favorites", "tags", "cloud", "bookmarks")
    val SHORTCUT_SECTIONS = setOf(HomeSection.STORAGE, HomeSection.QUICK_LOCATIONS, HomeSection.TOOLS)

    fun normalize(
        value: HomeCustomization,
        builtInShortcuts: List<HomeShortcut>,
    ): HomeCustomization {
        require(builtInShortcuts.size <= MAX_SHORTCUTS) { "Too many built-in shortcuts" }
        val defaults = builtInShortcuts
            .map { validateShortcut(it.copy(builtIn = true)) }
            .associateBy(HomeShortcut::id)
        require(defaults.size == builtInShortcuts.size) { "Built-in shortcut IDs must be unique" }

        val sections = buildList {
            value.sectionOrder.distinct().forEach(::add)
            HomeSection.entries.filterNot(::contains).forEach(::add)
        }
        val shortcuts = buildList {
            value.shortcuts.distinctBy(HomeShortcut::id).forEach { saved ->
                val currentDefault = defaults[saved.id]
                if (currentDefault != null) {
                    add(currentDefault.copy(visible = saved.visible, section = saved.section))
                } else if (!saved.builtIn) {
                    add(validateShortcut(saved.copy(builtIn = false)))
                }
            }
            defaults.values.filterNot { default -> any { it.id == default.id } }.forEach(::add)
        }
        require(shortcuts.size <= MAX_SHORTCUTS) { "Too many home shortcuts" }
        val storageOrder = value.storageOrder.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().take(MAX_SHORTCUTS).toList()
        val hiddenStorageIds = value.hiddenStorageIds.asSequence().map(String::trim).filter(String::isNotEmpty)
            .take(MAX_SHORTCUTS).toSet()
        val hiddenSections = value.hiddenSections.filterTo(linkedSetOf()) { it in HomeSection.entries }
        val toolOrder = buildList {
            value.toolOrder.filter { it in DEFAULT_TOOL_IDS }.distinct().forEach(::add)
            DEFAULT_TOOL_IDS.filterNot(::contains).forEach(::add)
        }
        val hiddenToolIds = value.hiddenToolIds.filterTo(linkedSetOf()) { it in DEFAULT_TOOL_IDS }
        return HomeCustomization(
            sectionOrder = sections,
            shortcuts = shortcuts,
            storageOrder = storageOrder,
            hiddenStorageIds = hiddenStorageIds,
            hiddenSections = hiddenSections,
            toolOrder = toolOrder,
            hiddenToolIds = hiddenToolIds,
        )
    }

    fun moveSection(value: HomeCustomization, section: HomeSection, offset: Int): HomeCustomization {
        val order = value.sectionOrder.toMutableList()
        val from = order.indexOf(section)
        if (from == -1) return value
        val to = (from + offset).coerceIn(0, order.lastIndex)
        if (from == to) return value
        order.add(to, order.removeAt(from))
        return value.copy(sectionOrder = order)
    }

    fun moveShortcut(value: HomeCustomization, id: String, offset: Int): HomeCustomization {
        val shortcuts = value.shortcuts.toMutableList()
        val from = shortcuts.indexOfFirst { it.id == id }
        if (from == -1) return value
        val to = (from + offset).coerceIn(0, shortcuts.lastIndex)
        if (from == to) return value
        shortcuts.add(to, shortcuts.removeAt(from))
        return value.copy(shortcuts = shortcuts)
    }

    fun setShortcutVisible(value: HomeCustomization, id: String, visible: Boolean): HomeCustomization =
        value.copy(shortcuts = value.shortcuts.map { if (it.id == id) it.copy(visible = visible) else it })

    fun setShortcutSection(value: HomeCustomization, id: String, section: HomeSection): HomeCustomization {
        require(section in SHORTCUT_SECTIONS) { "Shortcuts cannot be placed in this section" }
        return value.copy(shortcuts = value.shortcuts.map { if (it.id == id) it.copy(section = section) else it })
    }

    fun setSectionVisible(value: HomeCustomization, section: HomeSection, visible: Boolean): HomeCustomization =
        value.copy(hiddenSections = if (visible) value.hiddenSections - section else value.hiddenSections + section)

    fun orderedToolIds(value: HomeCustomization): List<String> = buildList {
        value.toolOrder.filter { it in DEFAULT_TOOL_IDS }.distinct().forEach(::add)
        DEFAULT_TOOL_IDS.filterNot(::contains).forEach(::add)
    }

    fun moveTool(value: HomeCustomization, id: String, offset: Int): HomeCustomization {
        val order = orderedToolIds(value).toMutableList()
        val from = order.indexOf(id)
        if (from == -1) return value
        val to = (from + offset).coerceIn(0, order.lastIndex)
        if (from == to) return value.copy(toolOrder = order)
        order.add(to, order.removeAt(from))
        return value.copy(toolOrder = order)
    }

    fun setToolVisible(value: HomeCustomization, id: String, visible: Boolean): HomeCustomization {
        require(id in DEFAULT_TOOL_IDS) { "Unknown home tool" }
        return value.copy(hiddenToolIds = if (visible) value.hiddenToolIds - id else value.hiddenToolIds + id)
    }

    fun addShortcut(value: HomeCustomization, shortcut: HomeShortcut): HomeCustomization {
        require(value.shortcuts.size < MAX_SHORTCUTS) { "Quick-location limit reached" }
        require(value.shortcuts.none { it.id == shortcut.id }) { "Shortcut ID already exists" }
        return value.copy(shortcuts = value.shortcuts + validateShortcut(shortcut.copy(builtIn = false)))
    }

    fun removeShortcut(value: HomeCustomization, id: String): HomeCustomization = value.copy(
        shortcuts = value.shortcuts.filterNot { it.id == id && !it.builtIn },
    )

    fun orderedStorageIds(value: HomeCustomization, availableIds: Collection<String>): List<String> {
        val available = availableIds.distinct()
        return buildList {
            value.storageOrder.filter { it in available }.forEach(::add)
            available.filterNot(::contains).forEach(::add)
        }
    }

    fun moveStorage(value: HomeCustomization, availableIds: Collection<String>, id: String, offset: Int): HomeCustomization {
        val order = orderedStorageIds(value, availableIds).toMutableList()
        val from = order.indexOf(id)
        if (from == -1) return value
        val to = (from + offset).coerceIn(0, order.lastIndex)
        if (from == to) return value.copy(storageOrder = order)
        order.add(to, order.removeAt(from))
        return value.copy(storageOrder = order)
    }

    fun setStorageVisible(value: HomeCustomization, id: String, visible: Boolean): HomeCustomization = value.copy(
        hiddenStorageIds = if (visible) value.hiddenStorageIds - id else value.hiddenStorageIds + id,
    )

    private fun validateShortcut(value: HomeShortcut): HomeShortcut {
        val id = value.id.trim()
        val title = value.title.trim()
        val path = value.path.trim()
        require(id.isNotEmpty() && id.length <= MAX_ID_LENGTH) { "Invalid shortcut ID" }
        require(title.isNotEmpty() && title.length <= MAX_TITLE_LENGTH) { "Invalid shortcut title" }
        require(path.isNotEmpty() && path.length <= MAX_PATH_LENGTH) { "Invalid shortcut path" }
        require(value.section in SHORTCUT_SECTIONS) { "Invalid shortcut section" }
        return value.copy(id = id, title = title, path = path)
    }
}
