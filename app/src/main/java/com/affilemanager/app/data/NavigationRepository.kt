package com.affilemanager.app.data

import android.content.Context
import com.affilemanager.app.model.SearchFilters
import com.affilemanager.app.model.EntryKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class RecentItem(
    val path: String,
    val openedAtMillis: Long,
)

data class SavedSearch(
    val id: String,
    val name: String,
    val rootPaths: List<String>,
    val query: String,
    val minBytes: Long?,
    val maxBytes: Long?,
    val modifiedAfter: Long?,
    val modifiedBefore: Long?,
    val kinds: Set<EntryKind>,
    val includeHidden: Boolean,
    val useRegex: Boolean,
    val tags: Set<String>,
) {
    val rootPath: String get() = rootPaths.firstOrNull().orEmpty()

    fun filters() = SearchFilters(
        query = query,
        minBytes = minBytes,
        maxBytes = maxBytes,
        modifiedAfter = modifiedAfter,
        modifiedBefore = modifiedBefore,
        kinds = kinds,
        includeHidden = includeHidden,
        useRegex = useRegex,
        tags = tags,
    )
}

class NavigationRepository(context: Context) {
    companion object {
        private const val PREFS = "navigation_v1"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_RECENTS = "recents"
        private const val KEY_SEARCHES = "saved_searches"
        private const val KEY_THUMBNAIL_DIRECTORIES = "thumbnail_directories"
        private const val MAX_FAVORITES = 100
        private const val MAX_RECENTS = 100
        private const val MAX_SEARCHES = 50
        private const val MAX_SEARCH_ROOTS = 32
        private const val MAX_THUMBNAIL_DIRECTORIES = 2_000
        private const val MAX_DIRECTORY_IDENTITY_LENGTH = 4_096
    }

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun favorites(): List<String> = readStringArray(KEY_FAVORITES, MAX_FAVORITES)

    fun toggleFavorite(path: String): List<String> {
        val canonical = File(path).canonicalPath
        val updated = favorites().toMutableList().apply {
            if (!remove(canonical)) add(canonical)
        }
        require(updated.size <= MAX_FAVORITES) { "Žymų riba viršyta" }
        writeStringArray(KEY_FAVORITES, updated)
        return updated
    }

    fun recents(): List<RecentItem> {
        val array = readArray(KEY_RECENTS, MAX_RECENTS)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            RecentItem(item.getString("path"), item.getLong("openedAt"))
        }
    }

    fun recordRecent(path: String): List<RecentItem> {
        val canonical = File(path).canonicalPath
        val updated = recents().filterNot { it.path == canonical }.toMutableList()
        updated.add(0, RecentItem(canonical, System.currentTimeMillis()))
        while (updated.size > MAX_RECENTS) updated.removeAt(updated.lastIndex)
        val array = JSONArray()
        updated.forEach { array.put(JSONObject().put("path", it.path).put("openedAt", it.openedAtMillis)) }
        commit(KEY_RECENTS, array)
        return updated
    }

    fun clearRecents() {
        check(preferences.edit().remove(KEY_RECENTS).commit()) { "Istorijos išvalyti nepavyko" }
    }

    fun thumbnailsEnabled(directoryIdentity: String): Boolean {
        val identity = validateDirectoryIdentity(directoryIdentity)
        return identity in readStringArray(KEY_THUMBNAIL_DIRECTORIES, MAX_THUMBNAIL_DIRECTORIES)
    }

    fun setThumbnailsEnabled(directoryIdentity: String, enabled: Boolean) {
        val identity = validateDirectoryIdentity(directoryIdentity)
        val current = readStringArray(KEY_THUMBNAIL_DIRECTORIES, MAX_THUMBNAIL_DIRECTORIES)
        val updated = current.filterNot { it == identity }.toMutableList()
        if (enabled) updated += identity
        while (updated.size > MAX_THUMBNAIL_DIRECTORIES) updated.removeAt(0)
        if (updated != current) writeStringArray(KEY_THUMBNAIL_DIRECTORIES, updated)
    }

    fun savedSearches(): List<SavedSearch> {
        val array = readArray(KEY_SEARCHES, MAX_SEARCHES)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val roots = item.optJSONArray("roots")?.let { rootsArray ->
                (0 until rootsArray.length()).map(rootsArray::getString)
            }.orEmpty().ifEmpty { listOf(item.getString("root")) }
            require(roots.size <= MAX_SEARCH_ROOTS) { "Išsaugotos paieškos vietų riba viršyta" }
            SavedSearch(
                id = item.getString("id"),
                name = item.getString("name"),
                rootPaths = roots,
                query = item.getString("query"),
                minBytes = item.optionalLong("minBytes"),
                maxBytes = item.optionalLong("maxBytes"),
                modifiedAfter = item.optionalLong("modifiedAfter"),
                modifiedBefore = item.optionalLong("modifiedBefore"),
                kinds = item.optJSONArray("kinds")?.let { kindsArray ->
                    (0 until kindsArray.length()).mapNotNull { kindIndex ->
                        runCatching { EntryKind.valueOf(kindsArray.getString(kindIndex)) }.getOrNull()
                    }.toSet()
                }.orEmpty(),
                includeHidden = item.optBoolean("hidden", false),
                useRegex = item.optBoolean("regex", false),
                tags = item.optJSONArray("tags")?.let { tagsArray ->
                    (0 until tagsArray.length()).map(tagsArray::getString).toSet()
                }.orEmpty(),
            )
        }
    }

    fun saveSearch(name: String, rootPaths: List<String>, filters: SearchFilters): List<SavedSearch> {
        require(name.isNotBlank()) { "Įveskite paieškos pavadinimą" }
        require(
            filters.query.isNotBlank() || filters.minBytes != null || filters.maxBytes != null ||
                filters.modifiedAfter != null || filters.modifiedBefore != null || filters.kinds.isNotEmpty() ||
                filters.tags.isNotEmpty(),
        ) { "Pasirinkite bent vieną paieškos sąlygą" }
        require(rootPaths.isNotEmpty()) { "Nepasirinkta paieškos vieta" }
        require(rootPaths.size <= MAX_SEARCH_ROOTS) { "Vienai paieškai galima išsaugoti iki $MAX_SEARCH_ROOTS vietų" }
        val updated = savedSearches().toMutableList()
        require(updated.size < MAX_SEARCHES) { "Išsaugotų paieškų riba viršyta" }
        updated += SavedSearch(
            id = UUID.randomUUID().toString(),
            name = name.trim().take(80),
            rootPaths = rootPaths.map { File(it).canonicalPath }.distinct(),
            query = filters.query,
            minBytes = filters.minBytes,
            maxBytes = filters.maxBytes,
            modifiedAfter = filters.modifiedAfter,
            modifiedBefore = filters.modifiedBefore,
            kinds = filters.kinds,
            includeHidden = filters.includeHidden,
            useRegex = filters.useRegex,
            tags = filters.tags,
        )
        writeSearches(updated)
        return updated
    }

    fun removeSearch(id: String): List<SavedSearch> {
        val updated = savedSearches().filterNot { it.id == id }
        writeSearches(updated)
        return updated
    }

    private fun writeSearches(searches: List<SavedSearch>) {
        val array = JSONArray()
        searches.forEach { search ->
            val roots = JSONArray().apply { search.rootPaths.forEach(::put) }
            val kinds = JSONArray().apply { search.kinds.sortedBy(Enum<*>::name).forEach { put(it.name) } }
            val tags = JSONArray().apply { search.tags.sorted().forEach(::put) }
            array.put(
                JSONObject()
                    .put("id", search.id)
                    .put("name", search.name)
                    .put("root", search.rootPath)
                    .put("roots", roots)
                    .put("query", search.query)
                    .put("minBytes", search.minBytes ?: JSONObject.NULL)
                    .put("maxBytes", search.maxBytes ?: JSONObject.NULL)
                    .put("modifiedAfter", search.modifiedAfter ?: JSONObject.NULL)
                    .put("modifiedBefore", search.modifiedBefore ?: JSONObject.NULL)
                    .put("kinds", kinds)
                    .put("hidden", search.includeHidden)
                    .put("regex", search.useRegex)
                    .put("tags", tags),
            )
        }
        commit(KEY_SEARCHES, array)
    }

    private fun validateDirectoryIdentity(value: String): String {
        val identity = value.trim()
        require(identity.isNotEmpty()) { "Katalogo tapatybė tuščia" }
        require(identity.length <= MAX_DIRECTORY_IDENTITY_LENGTH) { "Katalogo tapatybė per ilga" }
        return identity
    }

    private fun readStringArray(key: String, limit: Int): List<String> {
        val array = readArray(key, limit)
        return (0 until array.length()).map(array::getString)
    }

    private fun writeStringArray(key: String, values: List<String>) {
        val array = JSONArray()
        values.forEach(array::put)
        commit(key, array)
    }

    private fun readArray(key: String, limit: Int): JSONArray {
        val raw = preferences.getString(key, "[]") ?: "[]"
        require(raw.length <= 1_000_000) { "Nustatymų įrašas per didelis" }
        val array = JSONArray(raw)
        require(array.length() <= limit) { "Nustatymų elementų riba viršyta" }
        return array
    }

    private fun commit(key: String, value: JSONArray) {
        check(preferences.edit().putString(key, value.toString()).commit()) { "Nustatymų įrašyti nepavyko" }
    }

    private fun JSONObject.optionalLong(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
}
