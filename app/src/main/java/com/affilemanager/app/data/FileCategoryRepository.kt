package com.affilemanager.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.BaseColumns
import android.provider.MediaStore
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.FileSearchResult
import com.affilemanager.app.model.SearchFilters
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.model.StorageRootKind
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Keep cancellation wired until both the query and lazy cursor reads have finished. */
internal suspend fun <T> withMediaQueryCancellation(block: (CancellationSignal) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        continuation.resumeWith(runCatching { signal.throwIfCanceled(); block(signal) })
    }

enum class FileCategory(val kind: EntryKind) {
    IMAGES(EntryKind.IMAGE),
    VIDEOS(EntryKind.VIDEO),
    AUDIO(EntryKind.AUDIO),
    DOCUMENTS(EntryKind.DOCUMENT),
    ARCHIVES(EntryKind.ARCHIVE),
    APPS(EntryKind.APK),
    INSTALLED_APPS(EntryKind.APK),
}

data class FileCategoryResult(
    val entries: List<FileEntry>,
    val scannedRows: Int,
    val truncated: Boolean,
)

data class FileCategoryPage(
    val entries: List<FileEntry>,
    val scannedRows: Int,
    val nextOffset: Int?,
    val truncated: Boolean,
)

data class InstalledAppExportResult(
    val exportedApps: Int,
    val failedApps: Int,
)

internal object InstalledAppBackupRules {
    const val MAX_SELECTED_APPS = 50
    const val MAX_PACKAGE_PARTS = 64
    const val MAX_APP_BYTES = 5L * 1024L * 1024L * 1024L
    private const val MAX_STEM_LENGTH = 80
    private val unsafeNameCharacters = Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]")

    fun fileName(label: String, packageName: String, versionName: String?, split: Boolean): String {
        val readableLabel = label.trim().ifBlank { packageName }
        val rawStem = listOfNotNull(readableLabel, versionName?.trim()?.takeIf(String::isNotBlank)).joinToString("-")
        val safeStem = rawStem.replace(unsafeNameCharacters, "_").trim().trim('.').take(MAX_STEM_LENGTH)
            .ifBlank { packageName.replace(unsafeNameCharacters, "_").take(MAX_STEM_LENGTH) }
        return safeStem + if (split) ".apks" else ".apk"
    }

    fun zipEntryName(source: File, index: Int): String {
        if (index == 0) return "base.apk"
        val safe = source.name.replace(unsafeNameCharacters, "_").trim().ifBlank { "split-$index.apk" }
        return if (safe.endsWith(".apk", ignoreCase = true)) safe else "$safe.apk"
    }
}

enum class InstalledAppScope { USER, ALL, SYSTEM }

enum class CategoryStorageScope { ALL, INTERNAL, USB, SD_CARD }
enum class CategoryViewMode { FILES, FOLDERS }

internal object FileCategoryPagingRules {
    const val FIRST_PAGE_RESULTS = 160
    const val NEXT_PAGE_RESULTS = 240
    const val MAX_SCANNED_ROWS_PER_PAGE = 640
    const val BROWSE_PAGE_ROWS = 240

    fun literalSearchPattern(query: String): String = "%" + query.trim().take(200)
        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    fun resultLimit(offset: Int): Int = if (offset == 0) FIRST_PAGE_RESULTS else NEXT_PAGE_RESULTS

    fun nextOffset(
        offset: Int,
        scannedRows: Int,
        moreRowsAvailable: Boolean,
        maxQueryRows: Int,
    ): Int? {
        if (!moreRowsAvailable || scannedRows <= 0) return null
        val next = offset.toLong() + scannedRows.toLong()
        return next.takeIf { it < maxQueryRows.toLong() }?.toInt()
    }
}

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
        private const val MAX_CACHED_PAGES = 16
    }

    private data class PageKey(
        val category: FileCategory,
        val offset: Int,
        val sortMode: SortMode,
        val sortDirection: SortDirection,
        val showSystemApps: Boolean,
        val storageScope: CategoryStorageScope = CategoryStorageScope.ALL,
        val viewMode: CategoryViewMode = CategoryViewMode.FILES,
        val browseAll: Boolean = false,
        val query: String = "",
        val appScope: InstalledAppScope = InstalledAppScope.USER,
    )

    private data class CachedPage(val page: FileCategoryPage, val createdAtNanos: Long)
    private data class FolderKey(val category: FileCategory, val storageScope: CategoryStorageScope, val query: String)
    private data class CachedFolders(
        val entries: List<FileEntry>, val scannedRows: Int, val truncated: Boolean, val createdAtNanos: Long,
    )

    private data class CategoryQuery(
        val selection: String,
        val arguments: Array<String>,
    )

    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val cache = object : LinkedHashMap<PageKey, CachedPage>(MAX_CACHED_PAGES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PageKey, CachedPage>?): Boolean =
            size > MAX_CACHED_PAGES
    }
    private val cacheLock = Any()
    private val folderCache = object : LinkedHashMap<FolderKey, CachedFolders>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FolderKey, CachedFolders>?): Boolean = size > 4
    }

    suspend fun load(category: FileCategory, forceRefresh: Boolean = false): FileCategoryResult = withContext(Dispatchers.IO) {
        if (forceRefresh) invalidate(category)
        val entries = ArrayList<FileEntry>(minOf(MAX_RESULTS, 512))
        var scannedRows = 0
        var nextOffset: Int? = 0
        var truncated = false
        while (nextOffset != null && entries.size < MAX_RESULTS && scannedRows < MAX_QUERY_ROWS) {
            coroutineContext.ensureActive()
            val page = loadPage(
                category = category,
                offset = nextOffset,
                sortMode = SortMode.NAME,
                sortDirection = SortDirection.ASCENDING,
            )
            entries += page.entries
            scannedRows = (scannedRows + page.scannedRows).coerceAtMost(MAX_QUERY_ROWS)
            truncated = truncated || page.truncated
            nextOffset = page.nextOffset
        }
        if (entries.size >= MAX_RESULTS && nextOffset != null) truncated = true
        FileCategoryResult(
            entries = entries.distinctBy(FileEntry::absolutePath).take(MAX_RESULTS),
            scannedRows = scannedRows,
            truncated = truncated,
        )
    }

    suspend fun loadPage(
        category: FileCategory,
        offset: Int,
        sortMode: SortMode,
        sortDirection: SortDirection,
        forceRefresh: Boolean = false,
        showSystemApps: Boolean = false,
        storageScope: CategoryStorageScope = CategoryStorageScope.ALL,
        viewMode: CategoryViewMode = CategoryViewMode.FILES,
        appScope: InstalledAppScope = if (showSystemApps) InstalledAppScope.ALL else InstalledAppScope.USER,
    ): FileCategoryPage = withContext(Dispatchers.IO) {
        require(offset in 0 until MAX_QUERY_ROWS) { "Invalid category page offset" }
        if (forceRefresh) invalidate(category)
        val key = PageKey(category, offset, sortMode, sortDirection, showSystemApps, storageScope, viewMode, appScope = appScope)
        cached(key)?.let { return@withContext it }
        val page = queryCategoryPage(category, offset, sortMode, sortDirection, showSystemApps,
            storageScope = storageScope, viewMode = viewMode, appScope = appScope)
        synchronized(cacheLock) { cache[key] = CachedPage(page, System.nanoTime()) }
        page
    }

    fun invalidate(category: FileCategory) {
        synchronized(cacheLock) {
            cache.keys.removeAll { it.category == category }
            folderCache.keys.removeAll { it.category == category }
        }
    }

    /** A bounded window into the complete index; callers replace, rather than append, pages. */
    suspend fun loadBrowsePage(
        category: FileCategory,
        offset: Int,
        sortMode: SortMode,
        sortDirection: SortDirection,
        query: String = "",
        forceRefresh: Boolean = false,
        showSystemApps: Boolean = false,
        storageScope: CategoryStorageScope = CategoryStorageScope.ALL,
        viewMode: CategoryViewMode = CategoryViewMode.FILES,
        appScope: InstalledAppScope = if (showSystemApps) InstalledAppScope.ALL else InstalledAppScope.USER,
    ): FileCategoryPage = withContext(Dispatchers.IO) {
        require(offset >= 0 && offset % FileCategoryPagingRules.BROWSE_PAGE_ROWS == 0) { "Invalid category page offset" }
        val normalizedQuery = query.trim().take(200)
        if (forceRefresh) invalidate(category)
        val key = PageKey(category, offset, sortMode, sortDirection, showSystemApps, storageScope, viewMode, true, normalizedQuery, appScope)
        cached(key)?.let { return@withContext it }
        val page = queryCategoryPage(category, offset, sortMode, sortDirection, showSystemApps,
            storageScope = storageScope, viewMode = viewMode, browseAll = true, search = normalizedQuery, appScope = appScope)
        coroutineContext.ensureActive()
        synchronized(cacheLock) { cache[key] = CachedPage(page, System.nanoTime()) }
        page
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

    private suspend fun queryCategoryPage(
        category: FileCategory,
        offset: Int,
        sortMode: SortMode,
        sortDirection: SortDirection,
        showSystemApps: Boolean,
        storageScope: CategoryStorageScope = CategoryStorageScope.ALL,
        viewMode: CategoryViewMode = CategoryViewMode.FILES,
        browseAll: Boolean = false,
        search: String = "",
        appScope: InstalledAppScope = if (showSystemApps) InstalledAppScope.ALL else InstalledAppScope.USER,
    ): FileCategoryPage {
        val resultLimit = if (browseAll) FileCategoryPagingRules.BROWSE_PAGE_ROWS else FileCategoryPagingRules.resultLimit(offset)
        val rowLimit = if (browseAll) FileCategoryPagingRules.BROWSE_PAGE_ROWS else FileCategoryPagingRules.MAX_SCANNED_ROWS_PER_PAGE
        val maxQueryRows = if (browseAll) Int.MAX_VALUE else MAX_QUERY_ROWS
        if (viewMode == CategoryViewMode.FOLDERS && category != FileCategory.INSTALLED_APPS) {
            return queryFolderPage(category, storageScope, search, offset, resultLimit, sortMode, sortDirection)
        }
        if (category == FileCategory.INSTALLED_APPS) {
            val candidates = queryInstalledApps(resultLimit + 1, appScope, offset, sortMode, sortDirection, search)
            val entries = candidates.take(resultLimit)
            val hasMore = candidates.size > resultLimit
            val nextOffset = FileCategoryPagingRules.nextOffset(offset, entries.size, hasMore, if (browseAll) Int.MAX_VALUE else MAX_RESULTS)
            return FileCategoryPage(
                entries = entries,
                scannedRows = entries.size,
                nextOffset = nextOffset,
                truncated = hasMore && nextOffset == null,
            )
        }
        val roots = if (storageScope == CategoryStorageScope.ALL) emptyList() else localFiles.roots()
            .filter { root -> when (storageScope) {
                CategoryStorageScope.INTERNAL -> root.kind == StorageRootKind.INTERNAL
                CategoryStorageScope.USB -> root.kind == StorageRootKind.USB_STORAGE
                CategoryStorageScope.SD_CARD -> root.kind == StorageRootKind.SD_CARD
                CategoryStorageScope.ALL -> false
            } }
            .map { File(it.path).absolutePath.trimEnd('/') }
            .distinct()
        if (storageScope != CategoryStorageScope.ALL && roots.isEmpty()) {
            return FileCategoryPage(emptyList(), 0, null, false)
        }
        val workContext = coroutineContext
        return withMediaQueryCancellation { cancellationSignal ->
            val projection = arrayOf(
                BaseColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.MIME_TYPE,
            )
            val baseQuery = categoryQuery(category)
            val query = if (search.isBlank()) baseQuery else CategoryQuery(
                "(${baseQuery.selection}) AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
                baseQuery.arguments + FileCategoryPagingRules.literalSearchPattern(search),
            )
            val scopedQuery = if (roots.isEmpty()) query else {
                val clauses = roots.joinToString(" OR ") {
                    "(${MediaStore.MediaColumns.DATA} = ? OR ${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\')"
                }
                CategoryQuery("(${query.selection}) AND ($clauses)", query.arguments + roots.flatMap { root ->
                    listOf(root, root.replace("\\", "\\\\").replace("%", "\\%")
                        .replace("_", "\\_") + "/%")
                })
            }
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, scopedQuery.selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, scopedQuery.arguments)
                // Framework structured-sort synthesis can append DESC only to the
                // final column. Each allowlisted column needs its own direction.
                val direction = if (sortDirection == SortDirection.ASCENDING) "ASC" else "DESC"
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    mediaStoreSortColumns(sortMode).joinToString(", ") { "$it $direction" })
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putInt(ContentResolver.QUERY_ARG_LIMIT, rowLimit + if (browseAll) 1 else 0)
            }
            val entries = ArrayList<FileEntry>(resultLimit)
            var scanned = 0
            val moreRowsAvailable: Boolean
            val cursor = resolver.query(mediaQueryUri(offset, rowLimit + if (browseAll) 1 else 0), projection, queryArgs, cancellationSignal)
                ?: throw java.io.IOException("Failų sąrašo įkelti nepavyko")
            cursor.use {
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                while (
                    scanned < rowLimit &&
                    entries.size < resultLimit &&
                    cursor.moveToNext()
                ) {
                    workContext.ensureActive()
                    scanned += 1
                    val path = cursor.getString(pathIndex)?.takeIf(String::isNotBlank) ?: continue
                    val file = File(path)
                    val name = cursor.getString(nameIndex)?.takeIf(String::isNotBlank) ?: file.name
                    val mime = cursor.getString(mimeIndex)
                    val kind = FileSystemRules.detectKind(name, mime, isDirectory = false)
                    if (name.startsWith('.') || kind != category.kind) continue
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
                moreRowsAvailable = cursor.position + 1 < cursor.count || (!browseAll && cursor.count >= rowLimit)
            }
            val nextOffset = FileCategoryPagingRules.nextOffset(
                offset = offset,
                scannedRows = scanned,
                moreRowsAvailable = moreRowsAvailable,
                maxQueryRows = maxQueryRows,
            )
            FileCategoryPage(
                entries = entries.distinctBy(FileEntry::absolutePath),
                scannedRows = scanned,
                nextOffset = nextOffset,
                truncated = moreRowsAvailable && nextOffset == null,
            )
        }
    }

    private fun cached(key: PageKey): FileCategoryPage? = synchronized(cacheLock) {
        val cached = cache[key] ?: return@synchronized null
        if (System.nanoTime() - cached.createdAtNanos <= CACHE_TTL_NANOS) cached.page
        else null.also { cache.remove(key) }
    }

    private fun mediaQueryUri(offset: Int, limit: Int): android.net.Uri {
        val collection = MediaStore.Files.getContentUri("external")
        // Android 8/9 MediaProvider ignores the Bundle offset/limit arguments.
        // Its supported URI limit keeps queries bounded and avoids repeating page 1.
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            collection.buildUpon().appendQueryParameter("limit", "$offset,$limit").build()
        } else collection
    }

    private suspend fun queryFolderPage(
        category: FileCategory,
        storageScope: CategoryStorageScope,
        search: String,
        offset: Int,
        limit: Int,
        sortMode: SortMode,
        sortDirection: SortDirection,
    ): FileCategoryPage {
        val key = FolderKey(category, storageScope, search.trim().take(200))
        val snapshot = synchronized(cacheLock) {
            folderCache[key]?.takeIf { System.nanoTime() - it.createdAtNanos <= CACHE_TTL_NANOS }
        } ?: loadIndexedFolders(key).also { loaded ->
            synchronized(cacheLock) { folderCache[key] = loaded }
        }
        val byName = compareBy<FileEntry> { it.name.lowercase(Locale.ROOT) }.thenBy(FileEntry::absolutePath)
        val comparator = when (sortMode) {
            SortMode.NAME, SortMode.TYPE -> byName
            SortMode.SIZE -> compareBy<FileEntry> { it.sizeBytes }.then(byName)
            SortMode.MODIFIED -> compareBy<FileEntry> { it.modifiedAtMillis }.then(byName)
        }
        val ordered = snapshot.entries.sortedWith(
            if (sortDirection == SortDirection.ASCENDING) comparator else comparator.reversed(),
        )
        val page = ordered.drop(offset).take(limit)
        val next = (offset + page.size).takeIf { it < ordered.size }
        return FileCategoryPage(page, if (offset == 0) snapshot.scannedRows else 0, next, snapshot.truncated)
    }

    private suspend fun loadIndexedFolders(key: FolderKey): CachedFolders {
        val roots = localFiles.roots().filter { root -> when (key.storageScope) {
            CategoryStorageScope.ALL -> true
            CategoryStorageScope.INTERNAL -> root.kind == StorageRootKind.INTERNAL
            CategoryStorageScope.USB -> root.kind == StorageRootKind.USB_STORAGE
            CategoryStorageScope.SD_CARD -> root.kind == StorageRootKind.SD_CARD
        } }.map { File(it.path).absolutePath.trimEnd('/') }.distinct()
        if (key.storageScope != CategoryStorageScope.ALL && roots.isEmpty()) {
            return CachedFolders(emptyList(), 0, false, System.nanoTime())
        }
        val base = categoryQuery(key.category)
        val scoped = if (key.storageScope == CategoryStorageScope.ALL) base else {
            val clauses = roots.joinToString(" OR ") {
                "(${MediaStore.MediaColumns.DATA} = ? OR ${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\')"
            }
            CategoryQuery("(${base.selection}) AND ($clauses)", base.arguments + roots.flatMap { root ->
                listOf(root, root.replace("\\", "\\\\").replace("%", "\\%")
                    .replace("_", "\\_") + "/%")
            })
        }
        val workContext = coroutineContext
        return withMediaQueryCancellation { signal ->
            fun read(grouped: Boolean): CachedFolders {
                val cap = if (grouped) MAX_RESULTS else MAX_QUERY_ROWS
                val args = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, scoped.selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, scoped.arguments)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                        if (grouped) "${MediaStore.Files.FileColumns.PARENT} ASC" else "${MediaStore.MediaColumns.DATA} ASC")
                    putInt(ContentResolver.QUERY_ARG_LIMIT, cap + 1)
                    if (grouped) putString(ContentResolver.QUERY_ARG_SQL_GROUP_BY,
                        "${MediaStore.MediaColumns.VOLUME_NAME}, ${MediaStore.Files.FileColumns.PARENT}")
                }
                val projection = arrayOf(MediaStore.MediaColumns.DATA)
                val paths = LinkedHashSet<String>()
                var scanned = 0
                var duplicate = false
                val cursor = resolver.query(mediaQueryUri(0, cap + 1), projection, args, signal)
                    ?: throw java.io.IOException("Aplankų sąrašo įkelti nepavyko")
                val more: Boolean
                cursor.use {
                    val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                    while (scanned < cap && cursor.moveToNext()) {
                        workContext.ensureActive()
                        scanned++
                        val path = cursor.getString(pathIndex)?.takeIf(String::isNotBlank) ?: continue
                        val folder = File(path).parentFile ?: continue
                        if (folder.name.startsWith('.') || !folder.isDirectory || !folder.canRead()) continue
                        if (key.query.isNotBlank() && !folder.name.contains(key.query, ignoreCase = true)) continue
                        if (!paths.add(folder.absolutePath)) duplicate = true
                    }
                    more = cursor.moveToNext()
                }
                if (grouped && duplicate) throw IllegalStateException("MediaStore ignored grouped folder query")
                val entries = paths.asSequence().map(::File).map(localFiles::toEntry)
                    .take(MAX_RESULTS).toList()
                return CachedFolders(entries, scanned, more || paths.size > MAX_RESULTS, System.nanoTime())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { read(grouped = true) }
                catch (error: Exception) {
                    workContext.ensureActive()
                    signal.throwIfCanceled()
                    read(grouped = false)
                }
            } else read(grouped = false)
        }
    }

    private fun mediaStoreSortColumns(mode: SortMode): Array<String> = when (mode) {
        SortMode.NAME -> arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, BaseColumns._ID)
        SortMode.SIZE -> arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DISPLAY_NAME, BaseColumns._ID)
        SortMode.MODIFIED -> arrayOf(MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DISPLAY_NAME, BaseColumns._ID)
        SortMode.TYPE -> arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME, BaseColumns._ID)
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
            extensions = listOf("apk", "apks", "apkm", "xapk"),
        )
        FileCategory.INSTALLED_APPS -> error("Installed applications are obtained from PackageManager")
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

    suspend fun exportInstalledApps(
        entries: List<FileEntry>,
        destinationUri: String,
        safFiles: SafFileRepository,
        operation: OperationContext,
    ): InstalledAppExportResult = withContext(Dispatchers.IO) {
        val selected = entries
            .filter { !it.packageName.isNullOrBlank() }
            .distinctBy(FileEntry::packageName)
        require(selected.isNotEmpty()) { "Pasirinkite bent vieną įdiegtą programą" }
        require(selected.size <= InstalledAppBackupRules.MAX_SELECTED_APPS) {
            "Vienu metu galima išsaugoti iki ${InstalledAppBackupRules.MAX_SELECTED_APPS} programų"
        }
        operation.setTotals(selected.size, bytes = null)
        val temporaryRoot = Files.createTempDirectory(applicationContext.cacheDir.toPath(), "installed-app-export-").toFile()
        var exported = 0
        var failed = 0
        try {
            selected.forEach { entry ->
                operation.checkpoint()
                val appDirectory = Files.createTempDirectory(temporaryRoot.toPath(), "app-").toFile()
                try {
                    val backup = stageInstalledApp(entry, appDirectory)
                    safFiles.copyFromLocal(backup, destinationUri, operation).getOrThrow()
                    exported += 1
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed += 1
                } finally {
                    appDirectory.deleteRecursively()
                }
            }
        } finally {
            temporaryRoot.deleteRecursively()
        }
        if (failed > 0) operation.completeWithErrors(failed, "Išsaugota: $exported · nepavyko: $failed")
        else operation.note("Išsaugota programų: $exported")
        InstalledAppExportResult(exportedApps = exported, failedApps = failed)
    }

    private data class InstalledAppRecord(
        val info: ApplicationInfo, val source: File, val label: String, val bytes: Long, val modified: Long,
    )

    @Suppress("DEPRECATION")
    private suspend fun queryInstalledApps(
        limit: Int, appScope: InstalledAppScope, offset: Int, sortMode: SortMode, sortDirection: SortDirection, search: String,
    ): List<FileEntry> {
        val workContext = coroutineContext
        val packageManager = applicationContext.packageManager
        // Android exposes its finite installed-package inventory in one call. Retain only
        // lightweight sort keys here; package-version lookup and FileEntry construction are paged.
        val applications = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else packageManager.getInstalledApplications(0)
        val records = applications.asSequence()
            .filter { when (appScope) {
                InstalledAppScope.ALL -> true
                InstalledAppScope.USER -> !it.isSystemApplication()
                InstalledAppScope.SYSTEM -> it.isSystemApplication()
            } }
            .mapNotNull { info ->
                workContext.ensureActive()
                val source = File(info.sourceDir ?: return@mapNotNull null)
                if (!source.isFile || !source.canRead()) return@mapNotNull null
                val label = info.loadLabel(packageManager).toString().trim().ifBlank { info.packageName }
                if (search.isNotBlank() && !label.contains(search, ignoreCase = true)) return@mapNotNull null
                val bytes = info.splitSourceDirs.orEmpty().fold(source.length().coerceAtLeast(0L)) { total, path ->
                    runCatching { Math.addExact(total, File(path).length().coerceAtLeast(0L)) }.getOrDefault(Long.MAX_VALUE)
                }
                InstalledAppRecord(info, source, label, bytes, source.lastModified())
            }.toList()
        val byName = compareBy<InstalledAppRecord> { it.label.lowercase(Locale.ROOT) }.thenBy { it.info.packageName }
        val ascending = when (sortMode) {
            SortMode.NAME, SortMode.TYPE -> byName
            SortMode.SIZE -> compareBy<InstalledAppRecord> { it.bytes }.then(byName)
            SortMode.MODIFIED -> compareBy<InstalledAppRecord> { it.modified }.then(byName)
        }
        return records.sortedWith(if (sortDirection == SortDirection.ASCENDING) ascending else ascending.reversed())
            .asSequence().drop(offset).take(limit).map { record ->
                workContext.ensureActive()
                val version = runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        packageManager.getPackageInfo(record.info.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
                    } else packageManager.getPackageInfo(record.info.packageName, 0).versionName
                }.getOrNull()
                localFiles.toEntry(record.source).copy(
                    name = record.label, sizeBytes = record.bytes, packageName = record.info.packageName,
                    appVersionName = version, isSystemApp = record.info.isSystemApplication(),
                )
            }.toList()
    }

    @Suppress("DEPRECATION")
    internal suspend fun stageInstalledApp(entry: FileEntry, temporaryDirectory: File): File {
        val packageName = requireNotNull(entry.packageName) { "Programos paketo nustatyti nepavyko" }
        val packageManager = applicationContext.packageManager
        val applicationInfo = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            packageManager.getApplicationInfo(packageName, 0)
        }
        val sources = buildList {
            add(File(requireNotNull(applicationInfo.sourceDir) { "Programos APK nepasiekiamas" }))
            applicationInfo.splitSourceDirs.orEmpty().forEach { add(File(it)) }
        }
        require(sources.size <= InstalledAppBackupRules.MAX_PACKAGE_PARTS) { "Programos pakete per daug dalių" }
        require(sources.all { it.isFile && it.canRead() }) { "Programos APK nepasiekiamas" }
        val totalBytes = sources.fold(0L) { total, source -> Math.addExact(total, source.length().coerceAtLeast(0L)) }
        require(totalBytes <= InstalledAppBackupRules.MAX_APP_BYTES) { "Programos atsarginė kopija per didelė" }
        require(temporaryDirectory.usableSpace > totalBytes + 32L * 1024L * 1024L) { "Laikinai kopijai nepakanka vietos" }
        val output = File(
            temporaryDirectory,
            InstalledAppBackupRules.fileName(entry.name, packageName, entry.appVersionName, split = sources.size > 1),
        )
        require(output.canonicalFile.toPath().startsWith(temporaryDirectory.canonicalFile.toPath())) {
            "Netinkamas programos atsarginės kopijos vardas"
        }
        if (sources.size == 1) {
            output.outputStream().buffered().use { sink -> copyStream(sources.single(), sink) }
            require(output.length() == sources.single().length()) { "Programos APK kopijos dydis nesutampa" }
            return output
        }
        val expectedEntries = linkedMapOf<String, Long>()
        ZipOutputStream(BufferedOutputStream(output.outputStream())).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            val usedNames = mutableSetOf<String>()
            sources.forEachIndexed { index, source ->
                coroutineContext.ensureActive()
                val requested = InstalledAppBackupRules.zipEntryName(source, index)
                val extensionIndex = requested.lastIndexOf('.').takeIf { it > 0 } ?: requested.length
                val stem = requested.substring(0, extensionIndex)
                val extension = requested.substring(extensionIndex)
                var name = requested
                var suffix = 2
                while (!usedNames.add(name)) {
                    name = "$stem-$suffix$extension"
                    suffix += 1
                }
                expectedEntries[name] = source.length()
                zip.putNextEntry(ZipEntry(name))
                copyStream(source, zip)
                zip.closeEntry()
            }
        }
        ZipFile(output).use { archive ->
            require(archive.size() == expectedEntries.size) { "Programos atsarginės kopijos patikrinti nepavyko" }
            expectedEntries.forEach { (name, expectedBytes) ->
                require(archive.getEntry(name)?.size == expectedBytes) { "Programos atsarginės kopijos patikrinti nepavyko" }
            }
        }
        require(output.isFile && output.length() > 0L) { "Programos atsarginės kopijos sukurti nepavyko" }
        return output
    }

    private suspend fun copyStream(source: File, output: OutputStream) {
        val buffer = ByteArray(256 * 1024)
        source.inputStream().buffered().use { input ->
            var copied = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                copied = Math.addExact(copied, read.toLong())
            }
            require(copied == source.length()) { "Programos APK kopijos dydis nesutampa" }
        }
    }

    private fun ApplicationInfo.isSystemApplication(): Boolean =
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
}
