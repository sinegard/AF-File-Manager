package com.affilemanager.app.ui.components

import com.affilemanager.app.ui.localization.uiText

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import android.util.Log
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.SafEntry
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.StorageRoot
import com.affilemanager.app.model.StorageRootKind
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.thumbnail.ThumbnailDiskCache
import com.affilemanager.app.thumbnail.ThumbnailInvalidation
import com.affilemanager.app.thumbnail.ThumbnailLoadTracker
import com.affilemanager.app.thumbnail.ThumbnailSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

private const val MAX_THUMBNAIL_DIMENSION = 512
private const val MAX_MEMORY_CACHE_BYTES = 24 * 1_024 * 1_024
private const val MIN_MEMORY_CACHE_BYTES = 4 * 1_024 * 1_024
private const val MAX_MISSING_CACHE_ENTRIES = 256

internal val LocalStorageRoots = compositionLocalOf<List<StorageRoot>> { emptyList() }

internal object StorageLocationBadgeRules {
    fun kindForPath(absolutePath: String, roots: List<StorageRoot>): StorageRootKind? {
        val candidate = normalizedPath(absolutePath)
        var bestKind: StorageRootKind? = null
        var bestLength = -1
        roots.forEach { root ->
            if (root.kind == StorageRootKind.INTERNAL) return@forEach
            val rootPath = normalizedPath(root.path)
            if ((candidate == rootPath || candidate.startsWith("$rootPath/")) && rootPath.length > bestLength) {
                bestKind = root.kind
                bestLength = rootPath.length
            }
        }
        return bestKind
    }

    private fun normalizedPath(value: String): String {
        val normalized = value.replace('\\', '/')
        return if (normalized == "/") normalized else normalized.trimEnd('/')
    }
}

internal object FileVisualRules {
    fun boundedDimension(value: Int): Int = value.coerceIn(32, MAX_THUMBNAIL_DIMENSION)

    fun fitWithin(sourceWidth: Int, sourceHeight: Int, maximumWidth: Int, maximumHeight: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1 to 1
        val safeWidth = boundedDimension(maximumWidth)
        val safeHeight = boundedDimension(maximumHeight)
        val scale = minOf(1.0, safeWidth.toDouble() / sourceWidth, safeHeight.toDouble() / sourceHeight)
        return (sourceWidth * scale).roundToInt().coerceAtLeast(1) to
            (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    }

    fun sampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        val safeWidth = boundedDimension(targetWidth)
        val safeHeight = boundedDimension(targetHeight)
        var sample = 1
        while (sample <= Int.MAX_VALUE / 2) {
            val next = sample * 2
            if (sourceWidth / next < safeWidth || sourceHeight / next < safeHeight) break
            sample = next
        }
        return sample
    }

    fun extensionBadge(extension: String): String = extension.trim().trimStart('.').uppercase().take(4)

    fun showAccessLock(isReadable: Boolean): Boolean = !isReadable

    fun iconFamily(kind: EntryKind, extension: String): FileIconFamily {
        val ext = extension.trim().trimStart('.').lowercase()
        return when {
            kind == EntryKind.DIRECTORY -> FileIconFamily.FOLDER
            kind == EntryKind.IMAGE && ext == "svg" -> FileIconFamily.VECTOR_IMAGE
            kind == EntryKind.IMAGE -> FileIconFamily.IMAGE
            kind == EntryKind.VIDEO -> FileIconFamily.VIDEO
            kind == EntryKind.AUDIO -> FileIconFamily.AUDIO
            kind == EntryKind.DOCUMENT && ext == "pdf" -> FileIconFamily.PDF
            ext in setOf(
                "xml", "json", "yaml", "yml", "html", "htm", "css", "scss", "sass", "less",
                "lua", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "js", "jsx", "ts", "tsx",
                "py", "sh", "sql", "php", "rb", "go", "rs", "swift", "dart", "vue", "svelte", "smali",
                "gradle", "properties", "toml", "ini", "conf", "cfg", "proto", "graphql", "gql", "env", "gitignore",
            ) -> FileIconFamily.CODE
            ext in setOf("smil", "smi") -> FileIconFamily.PRESENTATION
            kind == EntryKind.DOCUMENT -> FileIconFamily.DOCUMENT
            kind == EntryKind.ARCHIVE -> FileIconFamily.ARCHIVE
            kind == EntryKind.APK -> FileIconFamily.APK
            else -> FileIconFamily.OTHER
        }
    }

    fun hasContentThumbnail(kind: EntryKind, extension: String): Boolean = when (kind) {
        EntryKind.IMAGE, EntryKind.VIDEO, EntryKind.AUDIO, EntryKind.APK -> true
        EntryKind.DOCUMENT -> extension.equals("pdf", ignoreCase = true)
        else -> false
    }

    fun localCacheKey(
        absolutePath: String,
        modifiedAtMillis: Long,
        sizeBytes: Long,
        kind: EntryKind,
        extension: String,
        widthPx: Int,
        heightPx: Int,
        showThumbnails: Boolean,
    ): String {
        val dimensions = "${boundedDimension(widthPx)}x${boundedDimension(heightPx)}"
        val normalizedExtension = extension.lowercase()
        val pathSpecific = kind == EntryKind.APK || (showThumbnails && hasContentThumbnail(kind, normalizedExtension))
        return if (pathSpecific) {
            "local-content|$absolutePath|$modifiedAtMillis|$sizeBytes|$kind|$normalizedExtension|$dimensions"
        } else {
            "local-type|$kind|$normalizedExtension|$dimensions"
        }
    }

    fun installedAppCacheKey(packageName: String, widthPx: Int, heightPx: Int): String =
        "installed-app|$packageName|${boundedDimension(widthPx)}x${boundedDimension(heightPx)}"
}

internal object PrivilegedAppDirectoryRules {
    private val packageNamePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun packageName(parentPath: String, entry: FileEntry): String? {
        if (!entry.isDirectory || !packageNamePattern.matches(entry.name)) return null
        val normalizedParent = parentPath.replace('\\', '/').trimEnd('/')
        return entry.name.takeIf {
            normalizedParent.endsWith("/Android/data") || normalizedParent.endsWith("/Android/obb")
        }
    }
}

internal enum class FileIconFamily {
    FOLDER,
    IMAGE,
    VECTOR_IMAGE,
    VIDEO,
    AUDIO,
    PDF,
    CODE,
    PRESENTATION,
    DOCUMENT,
    ARCHIVE,
    APK,
    OTHER,
}

internal data class LoadedFileVisual(
    val bitmap: Bitmap,
    val crop: Boolean,
    val contentThumbnail: Boolean,
    val sourceIdentity: String? = null,
    val sourceRevision: String? = null,
)

@Composable
fun LocalFileVisual(
    entry: FileEntry,
    targetWidth: Dp,
    targetHeight: Dp,
    showThumbnails: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val storageKind = if (entry.isDirectory) null else {
        StorageLocationBadgeRules.kindForPath(entry.absolutePath, LocalStorageRoots.current)
    }
    if (entry.isDirectory || !entry.isReadable) {
        FileVisualFrame(
            name = entry.name,
            kind = entry.kind,
            extension = entry.extension,
            visual = null,
            storageKind = storageKind,
            accessLocked = FileVisualRules.showAccessLock(entry.isReadable),
            modifier = modifier,
        )
        return
    }
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val widthPx = FileVisualRules.boundedDimension(with(density) { targetWidth.roundToPx() })
    val heightPx = FileVisualRules.boundedDimension(with(density) { targetHeight.roundToPx() })
    val key = localVisualKey(entry, widthPx, heightPx, showThumbnails)
    val cached = FileVisualLoader.peek(key)
    val visual = if (cached != null || FileVisualLoader.isKnownMissing(key)) {
        cached
    } else {
        val loaded by produceState<LoadedFileVisual?>(
            initialValue = null,
            key1 = key,
            key2 = context,
        ) {
            value = FileVisualLoader.loadLocal(context, entry, widthPx, heightPx, showThumbnails, key)
        }
        loaded
    }
    FileVisualFrame(
        name = entry.name,
        kind = entry.kind,
        extension = entry.extension,
        visual = visual,
        storageKind = storageKind,
        accessLocked = FileVisualRules.showAccessLock(entry.isReadable),
        modifier = modifier,
    )
}

@Composable
fun PrivilegedFileVisual(
    parentPath: String,
    entry: FileEntry,
    targetWidth: Dp,
    targetHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val storageKind = if (entry.isDirectory) null else {
        StorageLocationBadgeRules.kindForPath(entry.absolutePath, LocalStorageRoots.current)
    }
    val packageName = PrivilegedAppDirectoryRules.packageName(parentPath, entry)
    if (packageName == null) {
        LocalFileVisual(entry, targetWidth, targetHeight, showThumbnails = false, modifier = modifier)
        return
    }
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val widthPx = FileVisualRules.boundedDimension(with(density) { targetWidth.roundToPx() })
    val heightPx = FileVisualRules.boundedDimension(with(density) { targetHeight.roundToPx() })
    val key = FileVisualRules.installedAppCacheKey(packageName, widthPx, heightPx)
    val cached = FileVisualLoader.peek(key)
    val visual = if (cached != null || FileVisualLoader.isKnownMissing(key)) {
        cached
    } else {
        val loaded by produceState<LoadedFileVisual?>(initialValue = null, key1 = key, key2 = context) {
            value = FileVisualLoader.loadInstalledApp(context, packageName, widthPx, heightPx, key)
        }
        loaded
    }
    FileVisualFrame(
        name = entry.name,
        kind = entry.kind,
        extension = entry.extension,
        visual = visual,
        storageKind = storageKind,
        accessLocked = FileVisualRules.showAccessLock(entry.isReadable),
        modifier = modifier,
    )
}

@Composable
fun ProviderAppVisual(
    packageName: String?,
    fallbackIcon: ImageVector,
    targetSize: Dp,
    modifier: Modifier = Modifier,
) {
    if (packageName.isNullOrBlank()) {
        Icon(fallbackIcon, contentDescription = null, modifier = modifier)
        return
    }
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val sizePx = FileVisualRules.boundedDimension(with(density) { targetSize.roundToPx() })
    val key = FileVisualRules.installedAppCacheKey(packageName, sizePx, sizePx)
    val cached = FileVisualLoader.peek(key)
    val visual = if (cached != null || FileVisualLoader.isKnownMissing(key)) {
        cached
    } else {
        val loaded by produceState<LoadedFileVisual?>(initialValue = null, key1 = key, key2 = context) {
            value = FileVisualLoader.loadInstalledApp(context, packageName, sizePx, sizePx, key)
        }
        loaded
    }
    if (visual == null) {
        Icon(fallbackIcon, contentDescription = null, modifier = modifier)
    } else {
        Image(
            bitmap = visual.bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}

@Composable
fun SafFileVisual(
    entry: SafEntry,
    targetWidth: Dp,
    targetHeight: Dp,
    showThumbnails: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    if (entry.directory || !showThumbnails) {
        FileVisualFrame(
            name = entry.name,
            kind = entry.kind,
            extension = extension,
            visual = null,
            modifier = modifier,
        )
        return
    }
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val widthPx = FileVisualRules.boundedDimension(with(density) { targetWidth.roundToPx() })
    val heightPx = FileVisualRules.boundedDimension(with(density) { targetHeight.roundToPx() })
    val key = "saf|${entry.uri}|${entry.modifiedAtMillis}|${entry.sizeBytes}|$widthPx|$heightPx"
    val cached = FileVisualLoader.peek(key)
    val visual = if (cached != null) {
        cached
    } else {
        val loaded by produceState<LoadedFileVisual?>(
            initialValue = null,
            key1 = key,
            key2 = context,
        ) {
            value = FileVisualLoader.loadSaf(context, entry, widthPx, heightPx, key)
        }
        loaded
    }
    FileVisualFrame(
        name = entry.name,
        kind = entry.kind,
        extension = extension,
        visual = visual,
        modifier = modifier,
    )
}

/** Uses the same type icon and extension badge as local storage without downloading remote content. */
@Composable
fun RemoteFileVisual(
    entry: RemoteEntry,
    modifier: Modifier = Modifier,
) {
    val extension = entry.name.substringAfterLast('.', "").lowercase()
    FileVisualFrame(
        name = entry.name,
        kind = FileSystemRules.detectKind(entry.name, mimeType = null, isDirectory = entry.directory),
        extension = extension,
        visual = null,
        modifier = modifier,
    )
}

@Composable
private fun FileVisualFrame(
    name: String,
    kind: EntryKind,
    extension: String,
    visual: LoadedFileVisual?,
    storageKind: StorageRootKind? = null,
    accessLocked: Boolean = false,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    com.affilemanager.app.ui.theme.AppearanceContentOn(MaterialTheme.colorScheme.surfaceContainerHighest) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (visual != null) {
            val imageModifier = Modifier
                .fillMaxSize()
                .then(if (visual.crop) Modifier.clip(shape) else Modifier)
                .padding(if (visual.crop) 0.dp else 4.dp)
            Image(
                bitmap = visual.bitmap.asImageBitmap(),
                contentDescription = "$name ${uiText(if (visual.contentThumbnail) "miniatiūra" else "piktograma")}",
                contentScale = if (visual.crop) ContentScale.Crop else ContentScale.Fit,
                modifier = imageModifier,
            )
        } else {
            Icon(
                imageVector = fallbackIcon(kind, extension),
                contentDescription = uiText("$name failo tipo piktograma"),
                tint = fallbackColor(kind),
                modifier = Modifier.fillMaxSize().padding(7.dp),
            )
            val badge = FileVisualRules.extensionBadge(extension)
            if (!kind.equals(EntryKind.DIRECTORY) && badge.isNotEmpty()) {
                Text(
                    text = badge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(3.dp))
                        .padding(horizontal = 2.dp, vertical = 1.dp),
                )
            }
        }
        if (storageKind != null) {
            val (icon, label) = when (storageKind) {
                StorageRootKind.SD_CARD -> Icons.Rounded.SdStorage to "SD kortelė"
                StorageRootKind.USB_STORAGE -> Icons.Rounded.Usb to "USB saugykla"
                StorageRootKind.REMOVABLE -> Icons.Rounded.SdStorage to "Išimama saugykla"
                StorageRootKind.INTERNAL -> null to ""
            }
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = uiText(label),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(3.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(4.dp))
                        .padding(2.dp)
                        .size(14.dp),
                )
            }
        }
        if (accessLocked) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = uiText("Neprieinama"),
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(4.dp))
                    .padding(2.dp)
                    .size(14.dp),
            )
        }
    }
}

}

@Composable
private fun fallbackColor(kind: EntryKind) = when (kind) {
    EntryKind.DIRECTORY, EntryKind.APK -> MaterialTheme.colorScheme.primary
    EntryKind.IMAGE, EntryKind.ARCHIVE -> MaterialTheme.colorScheme.tertiary
    EntryKind.VIDEO, EntryKind.AUDIO -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun fallbackIcon(kind: EntryKind, extension: String): ImageVector = when (FileVisualRules.iconFamily(kind, extension)) {
    FileIconFamily.FOLDER -> Icons.Rounded.Folder
    FileIconFamily.IMAGE -> Icons.Rounded.Image
    FileIconFamily.VECTOR_IMAGE -> Icons.Rounded.Draw
    FileIconFamily.VIDEO -> Icons.Rounded.VideoFile
    FileIconFamily.AUDIO -> Icons.Rounded.AudioFile
    FileIconFamily.PDF -> Icons.Rounded.PictureAsPdf
    FileIconFamily.CODE -> Icons.Rounded.Code
    FileIconFamily.PRESENTATION -> Icons.Rounded.Slideshow
    FileIconFamily.DOCUMENT -> Icons.Rounded.Description
    FileIconFamily.ARCHIVE -> Icons.Rounded.Archive
    FileIconFamily.APK -> Icons.Rounded.Android
    FileIconFamily.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

private fun localVisualKey(entry: FileEntry, widthPx: Int, heightPx: Int, showThumbnails: Boolean): String =
    FileVisualRules.localCacheKey(
        absolutePath = entry.absolutePath,
        modifiedAtMillis = entry.modifiedAtMillis,
        sizeBytes = entry.sizeBytes,
        kind = entry.kind,
        extension = entry.extension,
        widthPx = widthPx,
        heightPx = heightPx,
        showThumbnails = showThumbnails,
    )

internal object FileVisualLoader {
    private val maximumCacheBytes = (Runtime.getRuntime().maxMemory() / 16L)
        .coerceIn(MIN_MEMORY_CACHE_BYTES.toLong(), MAX_MEMORY_CACHE_BYTES.toLong())
        .toInt()
    private val cache = object : LruCache<String, LoadedFileVisual>(maximumCacheBytes) {
        override fun sizeOf(key: String, value: LoadedFileVisual): Int =
            runCatching { value.bitmap.allocationByteCount }.getOrDefault(value.bitmap.byteCount).coerceAtLeast(1)
    }
    private val missingCache = object : LinkedHashMap<String, Unit>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_MISSING_CACHE_ENTRIES
    }
    private val decodePermits = Semaphore(2)
    private val activeLoads = ThumbnailLoadTracker()
    private val diskHits = AtomicLong()
    private val sourceDecodes = AtomicLong()
    private val diskWrites = AtomicLong()
    private val diskLock = Any()
    private var diskCache: ThumbnailDiskCache? = null
    private val cleanupRequests = Channel<Unit>(Channel.CONFLATED)
    private val reportedFailures = hashSetOf<String>()
    private var maintenanceStarted = false

    fun peek(key: String): LoadedFileVisual? = synchronized(cache) { cache.get(key) }

    internal fun isKnownMissing(key: String): Boolean = synchronized(missingCache) { missingCache.containsKey(key) }

    internal fun startMaintenance(context: Context, scope: CoroutineScope) {
        synchronized(diskLock) {
            if (maintenanceStarted) return
            maintenanceStarted = true
        }
        val application = context.applicationContext
        ThumbnailInvalidation.install(
            local = { invalidateLocal(application, it) },
            documents = { invalidateDocuments(application, it) },
            opened = ::requestCleanup,
        )
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { requestCleanup() }
        }
        optionalCache("AF-THUMB-OBSERVER") {
            application.contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        }
        scope.launch(Dispatchers.IO) {
            for (request in cleanupRequests) {
                delay(250L)
                val root = File(application.cacheDir, "af-thumbnails-v1")
                if (root.exists()) optionalCache("AF-THUMB-CLEANUP") {
                    pruneMissingMemorySources()
                    disk(application).pruneMissingLocalSources()
                }
            }
        }
        requestCleanup()
    }

    internal fun requestCleanup() { cleanupRequests.trySend(Unit) }

    internal suspend fun invalidateLocal(context: Context, paths: List<File>) = withContext(Dispatchers.IO) {
        val normalized = ThumbnailSources.localPaths(paths.map(File::getAbsolutePath))
        invalidateMemory { ThumbnailSources.affectedLocal(it, normalized) }
        optionalCache("AF-THUMB-INVALIDATE") { disk(context).invalidateLocal(paths.map(File::getAbsolutePath)) }
    }

    internal suspend fun invalidateDocuments(context: Context, uris: List<String>) = withContext(Dispatchers.IO) {
        val authorities = ThumbnailSources.documentAuthorities(uris)
        invalidateMemory { ThumbnailSources.affectedDocument(it, authorities) }
        optionalCache("AF-THUMB-INVALIDATE") { disk(context).invalidateDocuments(uris) }
    }

    private fun invalidateMemory(affected: (String) -> Boolean) = synchronized(cache) {
        activeLoads.invalidate(affected)
        cache.snapshot().filterValues { it.sourceIdentity?.let(affected) == true }.keys.forEach(cache::remove)
    }

    private fun pruneMissingMemorySources() {
        val candidates = synchronized(cache) { cache.snapshot().values.toList() }
        val currentRevisions = hashMapOf<String, String?>()
        val stale = hashSetOf<String>()
        candidates.forEach { visual ->
            val source = visual.sourceIdentity
            if (source != null && source.startsWith("local|")) {
                if (!currentRevisions.containsKey(source)) {
                    val file = File(source.removePrefix("local|"))
                    currentRevisions[source] = if (file.isFile) "${file.lastModified()}:${file.length()}" else null
                }
                if (currentRevisions[source] == null ||
                    (visual.sourceRevision != null && currentRevisions[source] != visual.sourceRevision)) stale += source
            }
        }
        if (stale.isNotEmpty()) invalidateMemory { it in stale }
    }

    /** Deterministic test isolation; production never clears a user's cache on launch. */
    internal fun resetMemoryAndDiskIndexForTests() {
        synchronized(cache) { cache.evictAll() }
        synchronized(missingCache) { missingCache.clear() }
        synchronized(diskLock) { diskCache = null }
    }

    internal fun cacheCounters(): List<Long> = listOf(diskHits.get(), sourceDecodes.get(), diskWrites.get())

    private fun disk(context: Context): ThumbnailDiskCache = synchronized(diskLock) {
        val directory = File(context.applicationContext.cacheDir, "af-thumbnails-v1")
        diskCache?.takeIf { it.directory == directory }
            ?: ThumbnailDiskCache(directory).also { diskCache = it }
    }

    private inline fun <T> optionalCache(code: String, block: () -> T): T? = try {
        block()
    } catch (_: IOException) {
        reportCacheFailure(code)
        null
    } catch (_: SecurityException) {
        reportCacheFailure(code)
        null
    } catch (_: IllegalArgumentException) {
        reportCacheFailure(code)
        null
    } catch (_: IllegalStateException) {
        reportCacheFailure(code)
        null
    }

    private fun reportCacheFailure(code: String) {
        if (synchronized(reportedFailures) { reportedFailures.add(code) }) Log.w("AFThumbnailCache", code)
    }

    private fun diskVisual(context: Context, key: String, source: String, revision: String): LoadedFileVisual? {
        val record = optionalCache("AF-THUMB-READ") {
            val store = disk(context)
            val before = store.corruptionCount
            val result = store.read(key, source, revision)
            if (store.corruptionCount > before) reportCacheFailure("AF-THUMB-CORRUPT")
            result
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(record.bytes, 0, record.bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_THUMBNAIL_DIMENSION || bounds.outHeight !in 1..MAX_THUMBNAIL_DIMENSION) {
            reportCacheFailure("AF-THUMB-BITMAP")
            return null
        }
        val bitmap = BitmapFactory.decodeByteArray(record.bytes, 0, record.bytes.size) ?: return null
        diskHits.incrementAndGet()
        return LoadedFileVisual(bitmap, record.crop, record.contentThumbnail, source, revision)
    }

    private fun persistVisual(context: Context, key: String, source: String, revision: String, visual: LoadedFileVisual, ticket: ThumbnailLoadTracker.Ticket) {
        if (!visual.contentThumbnail || !ticket.isCurrent) return
        if (visual.bitmap.width !in 1..MAX_THUMBNAIL_DIMENSION || visual.bitmap.height !in 1..MAX_THUMBNAIL_DIMENSION) return
        optionalCache("AF-THUMB-WRITE") {
            val output = ByteArrayOutputStream()
            if (visual.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                val record = ThumbnailDiskCache.Record(output.toByteArray(), visual.crop, visual.contentThumbnail)
                if (disk(context).write(key, source, revision, record) { ticket.isCurrent }) diskWrites.incrementAndGet()
            }
        }
    }

    private inline fun decodeVisual(block: () -> LoadedFileVisual?): LoadedFileVisual? = try {
        sourceDecodes.incrementAndGet()
        block()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        reportCacheFailure("AF-THUMB-DECODE")
        null
    } catch (_: RuntimeException) {
        reportCacheFailure("AF-THUMB-DECODE")
        null
    }

    suspend fun loadLocal(
        context: Context,
        entry: FileEntry,
        widthPx: Int,
        heightPx: Int,
        showThumbnails: Boolean = false,
        key: String = localVisualKey(entry, widthPx, heightPx, showThumbnails),
    ): LoadedFileVisual? {
        peek(key)?.let { return it }
        if (isKnownMissing(key)) return null
        if (entry.isDirectory || !entry.isReadable) return null
        return decodePermits.withPermit {
            peek(key)?.let { return@withPermit it }
            if (isKnownMissing(key)) return@withPermit null
            withContext(Dispatchers.IO) {
                val ticket = activeLoads.begin(ThumbnailSources.local(entry.absolutePath))
                try {
                    val source = ticket.source
                    val revision = "${entry.modifiedAtMillis}:${entry.sizeBytes}"
                    val sourcePath = File(source.removePrefix("local|")).toPath()
                    val temporarySource = sourcePath.startsWith(File(ThumbnailSources.localPath(context.cacheDir.path)).toPath()) ||
                        sourcePath.toString().contains("/${context.packageName}/cache/")
                    val persistent = showThumbnails && !temporarySource && entry.kind != EntryKind.APK &&
                        FileVisualRules.hasContentThumbnail(entry.kind, entry.extension)
                    if (persistent && (!entry.file.isFile || entry.file.lastModified() != entry.modifiedAtMillis || entry.file.length() != entry.sizeBytes)) {
                        optionalCache("AF-THUMB-INVALIDATE") { disk(context).invalidateLocal(listOf(entry.absolutePath)) }
                        return@withContext null
                    }
                    val cached = if (persistent) diskVisual(context, key, source, revision) else null
                    val visual = cached ?: decodeVisual {
                        createLocalVisual(context, entry, widthPx, heightPx, showThumbnails)?.copy(sourceIdentity = source, sourceRevision = revision)?.also { visual ->
                            if (persistent && entry.file.isFile && entry.file.lastModified() == entry.modifiedAtMillis && entry.file.length() == entry.sizeBytes) {
                                persistVisual(context, key, source, revision, visual, ticket)
                            }
                        }
                    }
                    val result = if (persistent && (!entry.file.isFile || entry.file.lastModified() != entry.modifiedAtMillis || entry.file.length() != entry.sizeBytes)) {
                        optionalCache("AF-THUMB-INVALIDATE") { disk(context).invalidateLocal(listOf(entry.absolutePath)) }
                        null
                    } else visual
                    if (!ticket.isCurrent) return@withContext null
                    result?.bitmap?.prepareToDraw()
                    cacheResult(key, result, rememberMissing = key.startsWith("local-type|"), ticket = ticket)
                    result
                } finally { activeLoads.finish(ticket) }
            }
        }
    }

    suspend fun loadSaf(
        context: Context,
        entry: SafEntry,
        widthPx: Int,
        heightPx: Int,
        key: String = "saf|${entry.uri}|${entry.modifiedAtMillis}|${entry.sizeBytes}|$widthPx|$heightPx",
    ): LoadedFileVisual? {
        peek(key)?.let { return it }
        if (isKnownMissing(key)) return null
        if (entry.directory) return null
        return decodePermits.withPermit {
            peek(key)?.let { return@withPermit it }
            if (isKnownMissing(key)) return@withPermit null
            val ticket = activeLoads.begin(ThumbnailSources.document(entry.uri))
            try {
            val result = withContext(Dispatchers.IO) {
                val source = ticket.source
                val revision = "${entry.modifiedAtMillis}:${entry.sizeBytes}"
                // Providers without a modification timestamp cannot promise a stable revision.
                val uri = entry.uri.toUri()
                val ownTemporaryProvider = uri.authority == "${context.packageName}.files" &&
                    uri.pathSegments.firstOrNull() == "app_cache"
                val persistent = !ownTemporaryProvider && entry.modifiedAtMillis > 0L && entry.sizeBytes > 0L
                val cached = if (persistent) diskVisual(context, key, source, revision) else null
                cached ?: decodeVisual {
                    createSafVisual(context, entry, widthPx, heightPx)?.copy(sourceIdentity = source, sourceRevision = revision)?.also { visual ->
                        if (persistent) persistVisual(context, key, source, revision, visual, ticket)
                    }
                }
            }
            if (!ticket.isCurrent) return@withPermit null
            result?.bitmap?.prepareToDraw()
            cacheResult(key, result, rememberMissing = false, ticket = ticket)
            result
            } finally { activeLoads.finish(ticket) }
        }
    }

    suspend fun loadInstalledApp(
        context: Context,
        packageName: String,
        widthPx: Int,
        heightPx: Int,
        key: String = FileVisualRules.installedAppCacheKey(packageName, widthPx, heightPx),
    ): LoadedFileVisual? {
        peek(key)?.let { return it }
        if (isKnownMissing(key)) return null
        return decodePermits.withPermit {
            peek(key)?.let { return@withPermit it }
            if (isKnownMissing(key)) return@withPermit null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    LoadedFileVisual(
                        bitmap = drawableBitmap(context.packageManager.getApplicationIcon(packageName), widthPx, heightPx),
                        crop = false,
                        contentThumbnail = false,
                    )
                }.getOrNull()
            }
            result?.bitmap?.prepareToDraw()
            cacheResult(key, result, rememberMissing = true)
            result
        }
    }

    private fun cacheResult(key: String, result: LoadedFileVisual?, rememberMissing: Boolean, ticket: ThumbnailLoadTracker.Ticket? = null) {
        if (result != null) {
            synchronized(cache) { if (ticket?.isCurrent != false) cache.put(key, result) }
            synchronized(missingCache) { missingCache.remove(key) }
        } else if (rememberMissing) {
            synchronized(missingCache) { missingCache[key] = Unit }
        }
    }

    private fun createLocalVisual(
        context: Context,
        entry: FileEntry,
        widthPx: Int,
        heightPx: Int,
        showThumbnails: Boolean,
    ): LoadedFileVisual? {
        val bitmap = when {
            entry.kind == EntryKind.APK -> apkIcon(context, entry.file, widthPx, heightPx)
                ?.let { LoadedFileVisual(it, crop = false, contentThumbnail = false) }
            !showThumbnails -> null
            entry.kind == EntryKind.IMAGE -> decodeLocalImage(entry.file, widthPx, heightPx)
                ?.let { LoadedFileVisual(it, crop = true, contentThumbnail = true) }
            entry.kind == EntryKind.VIDEO -> videoThumbnail(entry.file, widthPx, heightPx)
                ?.let { LoadedFileVisual(it, crop = true, contentThumbnail = true) }
            entry.kind == EntryKind.AUDIO -> embeddedAudioArt(entry.file, widthPx, heightPx)
                ?.let { LoadedFileVisual(it, crop = true, contentThumbnail = true) }
            entry.kind == EntryKind.DOCUMENT && entry.extension == "pdf" -> pdfThumbnail(entry.file, widthPx, heightPx)
                ?.let { LoadedFileVisual(it, crop = false, contentThumbnail = true) }
            else -> null
        }
        return bitmap
    }

    private fun createSafVisual(context: Context, entry: SafEntry, widthPx: Int, heightPx: Int): LoadedFileVisual? {
        val resolver = context.contentResolver
        val uri = entry.uri.toUri()
        val extension = entry.name.substringAfterLast('.', "").lowercase()
        val rich = when (entry.kind) {
            EntryKind.IMAGE, EntryKind.VIDEO -> {
                val systemThumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching { resolver.loadThumbnail(uri, Size(widthPx, heightPx), CancellationSignal()) }.getOrNull()
                } else null
                val fallback = if (systemThumbnail == null && entry.kind == EntryKind.IMAGE) {
                    decodeContentImage(context, uri, widthPx, heightPx)
                } else null
                (systemThumbnail ?: fallback)?.let {
                    LoadedFileVisual(scaleDown(it, widthPx, heightPx), crop = true, contentThumbnail = true)
                }
            }
            EntryKind.AUDIO -> embeddedAudioArt(context, uri, widthPx, heightPx)
                ?.let { LoadedFileVisual(it, crop = true, contentThumbnail = true) }
            EntryKind.DOCUMENT -> if (extension == "pdf") {
                pdfThumbnail(context, uri, widthPx, heightPx)
                    ?.let { LoadedFileVisual(it, crop = false, contentThumbnail = true) }
            } else null
            else -> null
        }
        return rich
    }

    private fun decodeLocalImage(file: File, widthPx: Int, heightPx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val (width, height) = FileVisualRules.fitWithin(info.size.width, info.size.height, widthPx, heightPx)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = FileVisualRules.sampleSize(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)?.let { scaleDown(it, widthPx, heightPx) }
    }

    private fun decodeContentImage(context: Context, uri: Uri, widthPx: Int, heightPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = FileVisualRules.sampleSize(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
        }?.let { scaleDown(it, widthPx, heightPx) }
    }

    @Suppress("DEPRECATION")
    private fun videoThumbnail(file: File, widthPx: Int, heightPx: Int): Bitmap? {
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createVideoThumbnail(file, Size(widthPx, heightPx), null)
        } else {
            ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
        }
        return bitmap?.let { scaleDown(it, widthPx, heightPx) }
    }

    private fun embeddedAudioArt(file: File, widthPx: Int, heightPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            decodeBytes(retriever.embeddedPicture, widthPx, heightPx)
        } finally {
            retriever.release()
        }
    }

    private fun embeddedAudioArt(context: Context, uri: Uri, widthPx: Int, heightPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            decodeBytes(retriever.embeddedPicture, widthPx, heightPx)
        } finally {
            retriever.release()
        }
    }

    private fun decodeBytes(bytes: ByteArray?, widthPx: Int, heightPx: Int): Bitmap? {
        if (bytes == null || bytes.isEmpty() || bytes.size > 32 * 1_024 * 1_024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = FileVisualRules.sampleSize(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.let { scaleDown(it, widthPx, heightPx) }
    }

    private fun pdfThumbnail(file: File, widthPx: Int, heightPx: Int): Bitmap? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)?.use { descriptor ->
            renderFirstPdfPage(descriptor, widthPx, heightPx)
        }

    private fun pdfThumbnail(context: Context, uri: Uri, widthPx: Int, heightPx: Int): Bitmap? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            renderFirstPdfPage(descriptor, widthPx, heightPx)
        }

    private fun renderFirstPdfPage(descriptor: ParcelFileDescriptor, widthPx: Int, heightPx: Int): Bitmap? {
        val renderer = android.graphics.pdf.PdfRenderer(descriptor)
        return try {
            if (renderer.pageCount == 0) return null
            renderer.openPage(0).use { page ->
                val (width, height) = FileVisualRules.fitWithin(page.width, page.height, widthPx, heightPx)
                createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        } finally {
            renderer.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun apkIcon(context: Context, file: File, widthPx: Int, heightPx: Int): Bitmap? {
        if (com.affilemanager.app.apk.SplitApkArchive.isBundle(file)) {
            val plan = com.affilemanager.app.apk.SplitApkArchive.plan(file)
            if (plan.base.bytes > 64L * 1024 * 1024) return null
            val directory = File(context.cacheDir, "apk-icons").apply { if (!isDirectory && !mkdirs()) return null }
            val temporary = File.createTempFile("base-", ".apk", directory)
            return try {
                com.affilemanager.app.apk.SplitApkArchive.copyPart(file, plan.base, temporary, 64L * 1024 * 1024)
                apkIcon(context, temporary, widthPx, heightPx)
            } finally { temporary.delete() }
        }
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            manager.getPackageArchiveInfo(file.absolutePath, 0)
        } ?: return null
        val applicationInfo = info.applicationInfo ?: return null
        applicationInfo.sourceDir = file.absolutePath
        applicationInfo.publicSourceDir = file.absolutePath
        return drawableBitmap(applicationInfo.loadIcon(manager), widthPx, heightPx)
    }

    private fun drawableBitmap(drawable: android.graphics.drawable.Drawable, widthPx: Int, heightPx: Int): Bitmap {
        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: widthPx
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: heightPx
        val (drawWidth, drawHeight) = FileVisualRules.fitWithin(intrinsicWidth, intrinsicHeight, widthPx, heightPx)
        val bitmap = createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val left = (widthPx - drawWidth) / 2
        val top = (heightPx - drawHeight) / 2
        drawable.bounds = Rect(left, top, left + drawWidth, top + drawHeight)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun scaleDown(bitmap: Bitmap, widthPx: Int, heightPx: Int): Bitmap {
        val (width, height) = FileVisualRules.fitWithin(bitmap.width, bitmap.height, widthPx, heightPx)
        return if (width == bitmap.width && height == bitmap.height) bitmap
        else bitmap.scale(width, height, true)
    }

}
