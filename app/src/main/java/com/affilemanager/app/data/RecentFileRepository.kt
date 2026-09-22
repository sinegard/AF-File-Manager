package com.affilemanager.app.data

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import com.affilemanager.app.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RecentFileItem(
    val entry: FileEntry,
    val recentAtMillis: Long,
)

data class RecentFilesSnapshot(
    val added: List<RecentFileItem>,
    val opened: List<RecentFileItem>,
) {
    val combined: List<RecentFileItem> = (added + opened)
        .groupBy { it.entry.absolutePath }
        .mapNotNull { (_, values) -> values.maxByOrNull(RecentFileItem::recentAtMillis) }
        .sortedWith(compareByDescending<RecentFileItem> { it.recentAtMillis }.thenBy { it.entry.name.lowercase() })
}

class RecentFileRepository(
    context: Context,
    private val localFiles: LocalFileRepository,
) {
    companion object {
        const val MAX_VISIBLE_ITEMS = 60
        private const val MAX_TRACKED_ITEMS = 200
        private const val MAX_MEDIA_ROWS = 200
        private const val MAX_PATH_LENGTH = 4_096
        private const val MAX_PREFERENCES_BYTES = 1_000_000
        private const val PREFS = "recent_files_v1"
        private const val KEY_TRACKED = "tracked"
        private const val KEY_ADDED = "added"
    }

    private data class Candidate(val file: File, val recentAtMillis: Long)

    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun record(path: String, recordedAtMillis: Long = System.currentTimeMillis()) {
        record(KEY_TRACKED, path, recordedAtMillis)
    }

    @Synchronized
    fun recordAdded(path: String, recordedAtMillis: Long = System.currentTimeMillis()) {
        record(KEY_ADDED, path, recordedAtMillis)
    }

    private fun record(key: String, path: String, recordedAtMillis: Long) {
        val file = File(path).canonicalFile
        if (!file.exists() || !file.canRead()) return
        require(file.absolutePath.length <= MAX_PATH_LENGTH) { "Failo kelias per ilgas" }
        val current = readTracked(key).filterNot { it.first == file.absolutePath }.toMutableList()
        current.add(0, file.absolutePath to recordedAtMillis.coerceAtLeast(0))
        while (current.size > MAX_TRACKED_ITEMS) current.removeAt(current.lastIndex)
        val array = JSONArray().apply {
            current.forEach { (storedPath, timestamp) ->
                put(JSONObject().put("path", storedPath).put("recordedAt", timestamp))
            }
        }
        check(preferences.edit().putString(key, array.toString()).commit()) {
            "Naujausių failų įrašo išsaugoti nepavyko"
        }
    }

    suspend fun latest(limit: Int = MAX_VISIBLE_ITEMS): List<RecentFileItem> = snapshot(limit).combined.take(limit)

    suspend fun snapshot(limit: Int = MAX_VISIBLE_ITEMS): RecentFilesSnapshot = withContext(Dispatchers.IO) {
        require(limit in 1..MAX_VISIBLE_ITEMS) { "Naujausių failų riba netinkama" }
        val opened = candidatesFromPreferences(KEY_TRACKED)
        val added = LinkedHashMap<String, Candidate>()
        candidatesFromPreferences(KEY_ADDED).forEach { candidate -> added[candidate.file.absolutePath] = candidate }
        queryMediaStore().forEach { candidate ->
            val path = candidate.file.absolutePath
            val existing = added[path]
            if (existing == null || candidate.recentAtMillis > existing.recentAtMillis) {
                added[path] = candidate
            }
        }
        RecentFilesSnapshot(
            added = added.values.toRecentItems(limit),
            opened = opened.toRecentItems(limit),
        )
    }

    private fun candidatesFromPreferences(key: String): List<Candidate> = readTracked(key).mapNotNull { (path, recordedAt) ->
        runCatching { File(path).canonicalFile }.getOrNull()
            ?.takeIf { it.exists() && it.canRead() && !it.isHidden }
            ?.let { file -> Candidate(file, recordedAt) }
    }

    private fun Collection<Candidate>.toRecentItems(limit: Int): List<RecentFileItem> =
        sortedWith(compareByDescending<Candidate> { it.recentAtMillis }.thenBy { it.file.name.lowercase() })
            .take(limit)
            .map { candidate -> RecentFileItem(localFiles.toEntry(candidate.file), candidate.recentAtMillis) }

    private fun queryMediaStore(): List<Candidate> = runCatching {
        val resolver = applicationContext.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATA} IS NOT NULL")
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_MEDIA_ROWS)
        }
        val result = ArrayList<Candidate>(MAX_MEDIA_ROWS)
        resolver.query(MediaStore.Files.getContentUri("external"), projection, queryArgs, null)?.use { cursor ->
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext() && result.size < MAX_MEDIA_ROWS) {
                val path = cursor.getString(pathIndex)?.takeIf { it.length in 1..MAX_PATH_LENGTH } ?: continue
                val file = runCatching { File(path).canonicalFile }.getOrNull() ?: continue
                if (!file.isFile || !file.canRead() || file.isHidden) continue
                val addedMillis = runCatching { Math.multiplyExact(cursor.getLong(addedIndex), 1_000L) }.getOrDefault(0L)
                val modifiedMillis = runCatching { Math.multiplyExact(cursor.getLong(modifiedIndex), 1_000L) }
                    .getOrDefault(file.lastModified().coerceAtLeast(0))
                result += Candidate(file, addedMillis.takeIf { it > 0L } ?: modifiedMillis.coerceAtLeast(file.lastModified().coerceAtLeast(0)))
            }
        }
        result
    }.getOrDefault(emptyList())

    private fun readTracked(key: String): List<Pair<String, Long>> {
        val raw = preferences.getString(key, "[]") ?: "[]"
        require(raw.length <= MAX_PREFERENCES_BYTES) { "Naujausių failų įrašas per didelis" }
        val array = JSONArray(raw)
        require(array.length() <= MAX_TRACKED_ITEMS) { "Naujausių failų riba viršyta" }
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val item = array.getJSONObject(index)
                val path = item.getString("path")
                require(path.length in 1..MAX_PATH_LENGTH && '\u0000' !in path)
                path to item.optLong("recordedAt", 0L).coerceAtLeast(0)
            }.getOrNull()
        }
    }
}
