package com.affilemanager.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.ContentFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class ContentFileRepository(private val context: Context) {
    companion object {
        private const val MAX_DISPLAY_NAME_LENGTH = 240
    }

    suspend fun describe(uri: Uri, suppliedMimeType: String?): Result<ContentFileEntry> = withContext(Dispatchers.IO) {
        runCatching {
            require(uri.scheme == "content") { "Palaikomos tik Android content nuorodos" }
            val resolver = context.contentResolver
            resolver.openFileDescriptor(uri, "r")?.use { } ?: throw IllegalArgumentException("Failo srautas nepasiekiamas")
            val isWritable = runCatching {
                resolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
            }.getOrDefault(false)

            var displayName: String? = null
            var sizeBytes: Long? = null
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            }.getOrNull()?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let { displayName = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { sizeBytes = cursor.getLong(it).coerceAtLeast(0) }
                }
            }

            val safeName = sanitizeDisplayName(displayName ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty())
            val mimeType = normalizeMimeType(suppliedMimeType)
                ?: normalizeMimeType(resolver.getType(uri))
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(safeName.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
            ContentFileEntry(
                uri = uri.toString(),
                name = safeName,
                kind = FileSystemRules.detectKind(safeName, mimeType),
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                modifiedAtMillis = null,
                isWritable = isWritable,
            )
        }
    }

    private fun sanitizeDisplayName(raw: String): String {
        val cleaned = raw
            .replace(Regex("[\\p{Cc}\\p{Cf}/\\\\]"), "_")
            .trim()
            .take(MAX_DISPLAY_NAME_LENGTH)
        return cleaned.ifBlank { "Atidaromas failas" }
    }

    private fun normalizeMimeType(value: String?): String? = value
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.matches(Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+*-]+")) && it != "*/*" }
}
