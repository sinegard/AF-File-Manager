package com.affilemanager.app.data

enum class DirectoryLayoutMode {
    LIST,
    GRID,
}

data class DirectoryDisplaySettings(
    val layoutMode: DirectoryLayoutMode = DirectoryLayoutMode.LIST,
    val iconScalePercent: Int = 100,
    val spacingScalePercent: Int = 100,
    val gridColumns: Int = 3,
    val showThumbnails: Boolean = false,
)

object DirectoryDisplayRules {
    const val MIN_ICON_SCALE_PERCENT = 70
    const val MAX_ICON_SCALE_PERCENT = 140
    const val MIN_SPACING_SCALE_PERCENT = 60
    const val MAX_SPACING_SCALE_PERCENT = 140
    const val MIN_GRID_COLUMNS = 1
    const val MAX_GRID_COLUMNS = 6

    fun requireValid(settings: DirectoryDisplaySettings): DirectoryDisplaySettings = settings.also {
        require(it.iconScalePercent in MIN_ICON_SCALE_PERCENT..MAX_ICON_SCALE_PERCENT) {
            "Icon scale is outside the supported range"
        }
        require(it.spacingScalePercent in MIN_SPACING_SCALE_PERCENT..MAX_SPACING_SCALE_PERCENT) {
            "Item spacing is outside the supported range"
        }
        require(it.gridColumns in MIN_GRID_COLUMNS..MAX_GRID_COLUMNS) {
            "Grid column count is outside the supported range"
        }
    }
}
