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
    }

    private data class Candidate(val file: File, val recentAtMillis: Long)

    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun record(path: String, recordedAtMillis: Long = System.currentTimeMillis()) {
        val file = File(path).canonicalFile
        if (!file.isFile || !file.canRead()) return
        require(file.absolutePath.length <= MAX_PATH_LENGTH) { "Failo kelias per ilgas" }
        val current = readTracked().filterNot { it.first == file.absolutePath }.toMutableList()
        current.add(0, file.absolutePath to recordedAtMillis.coerceAtLeast(0))
        while (current.size > MAX_TRACKED_ITEMS) current.removeAt(current.lastIndex)
        val array = JSONArray().apply {
            current.forEach { (storedPath, timestamp) ->
                put(JSONObject().put("path", storedPath).put("recordedAt", timestamp))
            }
        }
        check(preferences.edit().putString(KEY_TRACKED, array.toString()).commit()) {
            "Naujausių failų įrašo išsaugoti nepavyko"
        }
    }

    suspend fun latest(limit: Int = MAX_VISIBLE_ITEMS): List<RecentFileItem> = withContext(Dispatchers.IO) {
        require(limit in 1..MAX_VISIBLE_ITEMS) { "Naujausių failų riba netinkama" }
        val candidates = LinkedHashMap<String, Candidate>()

        readTracked().forEach { (path, recordedAt) ->
            runCatching { File(path).canonicalFile }.getOrNull()
                ?.takeIf { it.isFile && it.canRead() && !it.isHidden }
                ?.let { file -> candidates[file.absolutePath] = Candidate(file, recordedAt) }
        }

        queryMediaStore().forEach { candidate ->
            val path = candidate.file.absolutePath
            val existing = candidates[path]
            if (existing == null || candidate.recentAtMillis > existing.recentAtMillis) {
                candidates[path] = candidate
            }
        }

        candidates.values
            .sortedWith(compareByDescending<Candidate> { it.recentAtMillis }.thenBy { it.file.name.lowercase() })
            .take(limit)
            .map { candidate -> RecentFileItem(localFiles.toEntry(candidate.file), candidate.recentAtMillis) }
    }

    private fun queryMediaStore(): List<Candidate> = runCatching {
        val resolver = applicationContext.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATA} IS NOT NULL")
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_MEDIA_ROWS)
        }
        val result = ArrayList<Candidate>(MAX_MEDIA_ROWS)
        resolver.query(MediaStore.Files.getContentUri("external"), projection, queryArgs, null)?.use { cursor ->
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (cursor.moveToNext() && result.size < MAX_MEDIA_ROWS) {
                val path = cursor.getString(pathIndex)?.takeIf { it.length in 1..MAX_PATH_LENGTH } ?: continue
                val file = runCatching { File(path).canonicalFile }.getOrNull() ?: continue
                if (!file.isFile || !file.canRead() || file.isHidden) continue
                val modifiedMillis = runCatching { Math.multiplyExact(cursor.getLong(modifiedIndex), 1_000L) }
                    .getOrDefault(file.lastModified().coerceAtLeast(0))
                result += Candidate(file, modifiedMillis.coerceAtLeast(file.lastModified().coerceAtLeast(0)))
            }
        }
        result
    }.getOrDefault(emptyList())

    private fun readTracked(): List<Pair<String, Long>> {
        val raw = preferences.getString(KEY_TRACKED, "[]") ?: "[]"
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
