package com.affilemanager.app.ui

data class FileScrollKey(
    val tabId: String,
    val path: String,
    val grid: Boolean,
)

data class FileScrollPosition(
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0,
)

internal object ProgressiveScrollRules {
    fun startsPinnedToTop(initialPosition: FileScrollPosition): Boolean =
        initialPosition.firstVisibleItemIndex == 0 && initialPosition.firstVisibleItemScrollOffset == 0

    fun positionToPersist(
        pinnedToTop: Boolean,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ): FileScrollPosition = if (pinnedToTop) {
        FileScrollPosition()
    } else {
        FileScrollPosition(
            firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0),
        )
    }
}

class FileScrollPositionStore(
    private val maxEntries: Int = 256,
) {
    init {
        require(maxEntries > 0) { "Slinkties pozicijų riba turi būti teigiama" }
    }

    private val positions = object : LinkedHashMap<FileScrollKey, FileScrollPosition>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FileScrollKey, FileScrollPosition>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    fun read(key: FileScrollKey): FileScrollPosition = positions[key] ?: FileScrollPosition()

    @Synchronized
    fun write(key: FileScrollKey, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        positions[key] = FileScrollPosition(
            firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0),
        )
    }

    @Synchronized
    fun reset(tabId: String, path: String) {
        positions.keys.removeAll { key -> key.tabId == tabId && key.path == path }
    }

    @Synchronized
    internal fun size(): Int = positions.size
}
