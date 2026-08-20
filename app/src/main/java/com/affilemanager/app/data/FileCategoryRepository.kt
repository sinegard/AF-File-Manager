package com.affilemanager.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
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
    }

    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver

    suspend fun load(category: FileCategory): FileCategoryResult = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selectionFor(category))
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgsFor(category))
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, MAX_QUERY_ROWS)
        }
        val entries = ArrayList<FileEntry>(minOf(MAX_RESULTS, 512))
        var scanned = 0
        resolver.query(MediaStore.Files.getContentUri("external"), projection, queryArgs, null)?.use { cursor ->
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext() && scanned < MAX_QUERY_ROWS && entries.size < MAX_RESULTS) {
                coroutineContext.ensureActive()
                scanned += 1
                val path = cursor.getString(pathIndex)?.takeIf(String::isNotBlank) ?: continue
                val file = runCatching { File(path).canonicalFile }.getOrNull() ?: continue
                if (!file.isFile || !file.canRead() || file.isHidden) continue
                val entry = localFiles.toEntry(file)
                if (entry.kind == category.kind) entries += entry
            }
        }
        if (category == FileCategory.APPS && entries.size < MAX_RESULTS) {
            queryLaunchableApps(MAX_RESULTS - entries.size).forEach(entries::add)
        }
        FileCategoryResult(
            entries = entries.distinctBy(FileEntry::absolutePath).sortedBy { it.name.lowercase() },
            scannedRows = scanned,
            truncated = scanned >= MAX_QUERY_ROWS || entries.size >= MAX_RESULTS,
        )
    }

    private fun selectionFor(category: FileCategory): String = when (category) {
        FileCategory.IMAGES -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND ${MediaStore.MediaColumns.DATA} IS NOT NULL"
        FileCategory.VIDEOS -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND ${MediaStore.MediaColumns.DATA} IS NOT NULL"
        FileCategory.AUDIO -> "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND ${MediaStore.MediaColumns.DATA} IS NOT NULL"
        FileCategory.DOCUMENTS, FileCategory.ARCHIVES, FileCategory.APPS ->
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? AND ${MediaStore.MediaColumns.DATA} IS NOT NULL"
    }

    private fun selectionArgsFor(category: FileCategory): Array<String> = arrayOf(
        when (category) {
            FileCategory.IMAGES -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            FileCategory.VIDEOS -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            FileCategory.AUDIO -> MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
            FileCategory.DOCUMENTS, FileCategory.ARCHIVES, FileCategory.APPS -> MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
        }.toString(),
    )

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
