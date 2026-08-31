package com.affilemanager.app.data

enum class HomeSection {
    STORAGE,
    TOOLS,
    QUICK_LOCATIONS,
    RECENT_FILES,
}

data class HomeShortcut(
    val id: String,
    val title: String,
    val path: String,
    val visible: Boolean = true,
    val builtIn: Boolean = false,
)

data class HomeCustomization(
    val sectionOrder: List<HomeSection> = HomeSection.entries,
    val shortcuts: List<HomeShortcut> = emptyList(),
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
                    add(currentDefault.copy(visible = saved.visible))
                } else if (!saved.builtIn) {
                    add(validateShortcut(saved.copy(builtIn = false)))
                }
            }
            defaults.values.filterNot { default -> any { it.id == default.id } }.forEach(::add)
        }
        require(shortcuts.size <= MAX_SHORTCUTS) { "Too many home shortcuts" }
        return HomeCustomization(sectionOrder = sections, shortcuts = shortcuts)
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

    fun addShortcut(value: HomeCustomization, shortcut: HomeShortcut): HomeCustomization {
        require(value.shortcuts.size < MAX_SHORTCUTS) { "Quick-location limit reached" }
        require(value.shortcuts.none { it.id == shortcut.id }) { "Shortcut ID already exists" }
        return value.copy(shortcuts = value.shortcuts + validateShortcut(shortcut.copy(builtIn = false)))
    }

    fun removeShortcut(value: HomeCustomization, id: String): HomeCustomization = value.copy(
        shortcuts = value.shortcuts.filterNot { it.id == id && !it.builtIn },
    )

    private fun validateShortcut(value: HomeShortcut): HomeShortcut {
        val id = value.id.trim()
        val title = value.title.trim()
        val path = value.path.trim()
        require(id.isNotEmpty() && id.length <= MAX_ID_LENGTH) { "Invalid shortcut ID" }
        require(title.isNotEmpty() && title.length <= MAX_TITLE_LENGTH) { "Invalid shortcut title" }
        require(path.isNotEmpty() && path.length <= MAX_PATH_LENGTH) { "Invalid shortcut path" }
        return value.copy(id = id, title = title, path = path)
    }
}
