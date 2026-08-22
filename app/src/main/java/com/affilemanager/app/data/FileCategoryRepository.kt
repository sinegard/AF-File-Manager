package com.affilemanager.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.FileSearchResult
import com.affilemanager.app.model.SearchFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

enum class FileCategory(val kind: EntryKind) {
    IMAGES(EntryKind.IMAGE),
    VIDEOS(EntryKind.VIDEO),
    AUDIO(EntryKind.AUDIO),
    DOCUMENTS(EntryKind.DOCUMENT),
    ARCHIVES(EntryKind.ARCHIVE),
    APPS(EntryKind.APK),
}

data class FileCategoryResult(
    val entries: List<FileEntry>,
    val scannedRows: Int,
    val truncated: Boolean,
)

/**
 * Bounded MediaStore-backed virtual folders. Opening a category never performs a
 * recursive filesystem walk on the UI thread, and the coroutine remains cancellable.
 */
class FileCategoryRepository(
    context: Context,
    private val localFiles: LocalFileRepository,
) {
    companion object {
        const val MAX_QUERY_ROWS = 10_000
        const val MAX_RESULTS = 5_000
        private const val CACHE_TTL_NANOS = 60L * 1_000L * 1_000L * 1_000L
    }

    private data class CachedCategory(val result: FileCategoryResult, val createdAtNanos: Long)

    private data class CategoryQuery(
        val selection: String,
        val arguments: Array<String>,
    )

    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val cache = mutableMapOf<FileCategory, CachedCategory>()
    private val cacheLock = Any()

    suspend fun load(category: FileCategory, forceRefresh: Boolean = false): FileCategoryResult = withContext(Dispatchers.IO) {
        if (!forceRefresh) cached(category)?.let { return@withContext it }
        val result = queryCategory(category)
        synchronized(cacheLock) {
            cache[category] = CachedCategory(result, System.nanoTime())
        }
        result
    }

    /**
     * Uses Android's existing media index when every requested type is file-backed.
     * Directory/other searches intentionally fall back to the bounded filesystem walk.
     */
    suspend fun searchIndexed(
        roots: List<String>,
        filters: SearchFilters,
        pathPredicate: (FileEntry) -> Boolean = { true },
    ): FileSearchResult? = withContext(Dispatchers.IO) {
        val requestedCategories = filters.kinds.mapNotNull(::categoryForKind).distinct()
        if (filters.kinds.isEmpty() || requestedCategories.size != filters.kinds.size) return@withContext null
        val normalizedRoots = roots.asSequence()
            .mapNotNull { runCatching { File(it).absoluteFile.toPath().normalize() }.getOrNull() }
            .distinct()
            .toList()
        if (normalizedRoots.isEmpty()) return@withContext null
        val regex = if (filters.useRegex && filters.query.isNotBlank()) {
            runCatching { Regex(filters.query, RegexOption.IGNORE_CASE) }.getOrElse { return@withContext null }
        } else null
        val results = ArrayList<FileEntry>(minOf(MAX_RESULTS, 512))
        var scanned = 0
        var truncated = false
        requestedCategories.forEach { category ->
            coroutineContext.ensureActive()
            val categoryResult = load(category)
            scanned = (scanned + categoryResult.scannedRows).coerceAtMost(MAX_QUERY_ROWS * FileCategory.entries.size)
            truncated = truncated || categoryResult.truncated
            categoryResult.entries.forEach { entry ->
                if (results.size >= MAX_RESULTS) {
                    truncated = true
                    return@forEach
                }
                val path = runCatching { File(entry.absolutePath).absoluteFile.toPath().normalize() }.getOrNull() ?: return@forEach
                if (normalizedRoots.none(path::startsWith)) return@forEach
                if (!filters.includeHidden && (entry.isHidden || entry.name.startsWith('.'))) return@forEach
                val nameMatches = when {
                    filters.query.isBlank() -> true
                    regex != null -> regex.containsMatchIn(entry.name)
                    else -> entry.name.contains(filters.query, ignoreCase = true)
                }
                if (!nameMatches ||
                    (filters.minBytes != null && entry.sizeBytes < filters.minBytes) ||
                    (filters.maxBytes != null && entry.sizeBytes > filters.maxBytes) ||
                    (filters.modifiedAfter != null && entry.modifiedAtMillis < filters.modifiedAfter) ||
                    (filters.modifiedBefore != null && entry.modifiedAtMillis > filters.modifiedBefore) ||
                    !pathPredicate(entry)
                ) return@forEach
                results += entry
            }
        }
        FileSearchResult(
            entries = results.distinctBy(FileEntry::absolutePath)
                .sortedWith(compareBy<FileEntry> { it.name.lowercase(Locale.ROOT) }.thenBy(FileEntry::absolutePath)),
            scannedEntries = scanned,
            truncated = truncated,
        )
    }

    private suspend fun queryCategory(category: FileCategory): FileCategoryResult {
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val query = categoryQuery(category)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, query.selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, query.arguments)
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_QUERY_ROWS)
        }
        val entries = ArrayList<FileEntry>(minOf(MAX_RESULTS, 512))
        if (category == FileCategory.APPS) entries += queryLaunchableApps(MAX_RESULTS)
        var scanned = 0
        resolver.query(MediaStore.Files.getContentUri("external"), projection, queryArgs, null)?.use { cursor ->
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext() && scanned < MAX_QUERY_ROWS && entries.size < MAX_RESULTS) {
                coroutineContext.ensureActive()
                scanned += 1
                val path = cursor.getString(pathIndex)?.takeIf(String::isNotBlank) ?: continue
                val file = File(path)
                val name = cursor.getString(nameIndex)?.takeIf(String::isNotBlank) ?: file.name
                val mime = cursor.getString(mimeIndex)
                val kind = FileSystemRules.detectKind(name, mime, isDirectory = false)
                if (kind != category.kind || name.startsWith('.') || !file.isFile || !file.canRead()) continue
                entries += FileEntry(
                    absolutePath = file.absolutePath,
                    name = name,
                    kind = kind,
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L),
                    modifiedAtMillis = cursor.getLong(modifiedIndex).coerceAtLeast(0L) * 1_000L,
                    isHidden = false,
                    isReadable = true,
                    isWritable = file.canWrite(),
                )
            }
        }
        return FileCategoryResult(
            entries = entries.distinctBy(FileEntry::absolutePath).sortedBy { it.name.lowercase() },
            scannedRows = scanned,
            truncated = scanned >= MAX_QUERY_ROWS || entries.size >= MAX_RESULTS,
        )
    }

    private fun cached(category: FileCategory): FileCategoryResult? = synchronized(cacheLock) {
        val cached = cache[category] ?: return@synchronized null
        if (System.nanoTime() - cached.createdAtNanos <= CACHE_TTL_NANOS) cached.result
        else null.also { cache.remove(category) }
    }

    private fun categoryQuery(category: FileCategory): CategoryQuery = when (category) {
        FileCategory.IMAGES -> mediaTypeQuery(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
        FileCategory.VIDEOS -> mediaTypeQuery(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
        FileCategory.AUDIO -> mediaTypeQuery(MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO)
        FileCategory.DOCUMENTS -> mimeAndExtensionQuery(
            mimeTypes = listOf(
                "application/pdf", "application/json", "application/xml", "application/epub+zip",
                "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            ),
            extensions = listOf(
                "pdf", "txt", "md", "csv", "json", "xml", "yaml", "yml", "log", "html", "htm", "smil", "smi",
                "lua", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "js", "ts", "py", "sh", "sql",
                "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "epub",
            ),
            includeTextMime = true,
        )
        FileCategory.ARCHIVES -> mimeAndExtensionQuery(
            mimeTypes = listOf(
                "application/zip", "application/x-7z-compressed", "application/vnd.rar", "application/x-rar-compressed",
                "application/x-tar", "application/gzip", "application/x-bzip2", "application/x-xz",
            ),
            extensions = listOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "jar"),
        )
        FileCategory.APPS -> mimeAndExtensionQuery(
            mimeTypes = listOf("application/vnd.android.package-archive"),
            extensions = listOf("apk", "apks", "xapk"),
        )
    }

    private fun mediaTypeQuery(mediaType: Int) = CategoryQuery(
        selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND ${MediaStore.MediaColumns.DATA} IS NOT NULL",
        arguments = arrayOf(mediaType.toString()),
    )

    private fun mimeAndExtensionQuery(
        mimeTypes: List<String>,
        extensions: List<String>,
        includeTextMime: Boolean = false,
    ): CategoryQuery {
        val conditions = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        if (includeTextMime) {
            conditions += "${MediaStore.MediaColumns.MIME_TYPE} LIKE ?"
            arguments += "text/%"
        }
        if (mimeTypes.isNotEmpty()) {
            conditions += "${MediaStore.MediaColumns.MIME_TYPE} IN (${mimeTypes.joinToString(",") { "?" }})"
            arguments += mimeTypes
        }
        extensions.forEach { extension ->
            conditions += "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            arguments += "%.${extension.lowercase(Locale.ROOT)}"
        }
        return CategoryQuery(
            selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND ${MediaStore.MediaColumns.DATA} IS NOT NULL AND (${conditions.joinToString(" OR ")})",
            arguments = (listOf(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE.toString()) + arguments).toTypedArray(),
        )
    }

    private fun categoryForKind(kind: EntryKind): FileCategory? = when (kind) {
        EntryKind.IMAGE -> FileCategory.IMAGES
        EntryKind.VIDEO -> FileCategory.VIDEOS
        EntryKind.AUDIO -> FileCategory.AUDIO
        EntryKind.DOCUMENT -> FileCategory.DOCUMENTS
        EntryKind.ARCHIVE -> FileCategory.ARCHIVES
        EntryKind.APK -> FileCategory.APPS
        EntryKind.DIRECTORY, EntryKind.OTHER -> null
    }

    @Suppress("DEPRECATION")
    private fun queryLaunchableApps(limit: Int): List<FileEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = if (Build.VERSION.SDK_INT >= 33) {
            applicationContext.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            applicationContext.packageManager.queryIntentActivities(intent, 0)
        }
        return matches.asSequence()
            .mapNotNull { info ->
                val applicationInfo = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                val file = File(applicationInfo.sourceDir ?: return@mapNotNull null)
                if (!file.isFile || !file.canRead()) return@mapNotNull null
                val label = applicationInfo.loadLabel(applicationContext.packageManager).toString().trim()
                localFiles.toEntry(file).copy(name = "${label.ifBlank { applicationInfo.packageName }}.apk")
            }
            .distinctBy(FileEntry::absolutePath)
            .take(limit.coerceIn(0, MAX_RESULTS))
            .toList()
    }
}
