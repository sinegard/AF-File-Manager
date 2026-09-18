package com.affilemanager.app.transfer

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileCategoryRepository
import com.affilemanager.app.model.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class PreparedNearbyTransfer(
    val paths: List<String>,
    val relativePaths: List<String> = paths.map { File(it).name },
    val directories: List<String> = emptyList(),
    val cleanupRootPath: String? = null,
    val fileSizes: List<Long> = emptyList(),
    val totalBytes: Long = fileSizes.sum(),
    /** A non-null entry is streamed from ContentResolver instead of being duplicated in cache. */
    val sourceUris: List<String?> = List(paths.size) { null },
) {
    init {
        require(paths.size == relativePaths.size && paths.size == sourceUris.size) {
            "Siuntimo rinkinio keliai nesutampa"
        }
    }

    companion object {
        fun empty(): PreparedNearbyTransfer = PreparedNearbyTransfer(emptyList(), emptyList())
    }
}

data class NearbyContact(
    val lookupKey: String,
    val displayName: String,
    val photoUri: String? = null,
)

data class NearbyContactPage(
    val contacts: List<NearbyContact>,
    val truncated: Boolean,
)

class NearbySourcePreparer(
    private val application: Application,
    private val fileCategories: FileCategoryRepository,
) {
    companion object {
        const val MAX_FILES = 8_000
        const val MAX_DIRECTORIES = 16_000
        const val MAX_TOTAL_BYTES = 60L * 1_024L * 1_024L * 1_024L
        const val MAX_PATH_PAYLOAD_CHARS = 4_000_000
        const val MAX_CONTACTS = 1_000
        private const val MAX_DEPTH = 64
        private const val BUFFER_SIZE = 256 * 1_024
    }

    private val stagingRoot = File(application.cacheDir, "nearby-send-staging")

    suspend fun prepareEntries(entries: Collection<FileEntry>, installedApps: Boolean): Result<PreparedNearbyTransfer> =
        withContext(Dispatchers.IO) {
            runCatching {
                val selected = entries.distinctBy(FileEntry::absolutePath)
                require(selected.isNotEmpty()) { "Pasirinkite bent vieną failą" }
                require(selected.size <= MAX_FILES) { "Vienu kartu galima siųsti iki $MAX_FILES failų" }
                if (!installedApps) {
                    return@runCatching prepareLocalNodes(selected.map { File(it.absolutePath) })
                }

                val stage = newStage()
                try {
                    val files = selected.mapIndexed { index, entry ->
                        coroutineContext.ensureActive()
                        val directory = File(stage, index.toString()).apply {
                            require(mkdir()) { "Laikinos programos kopijos sukurti nepavyko" }
                        }
                        fileCategories.stageInstalledApp(entry, directory)
                    }
                    validatedFiles(files, files.map(File::getName), emptyList(), stage)
                } catch (error: Throwable) {
                    stage.deleteRecursively()
                    throw error
                }
            }
        }

    suspend fun prepareContentUris(
        uris: Collection<Uri>,
        copyToPrivateStage: Boolean = true,
    ): Result<PreparedNearbyTransfer> {
        var ownedStage: File? = null
        var delivered = false
        try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val selected = uris.distinctBy(Uri::toString)
                    require(selected.isNotEmpty()) { "Pasirinkite bent vieną failą" }
                    require(selected.size <= MAX_FILES) { "Vienu kartu galima siųsti iki $MAX_FILES failų" }
                    if (copyToPrivateStage) {
                        val stage = newStage().also { ownedStage = it }
                        copyContentUris(selected, stage)
                    } else {
                        prepareDirectContentUris(selected) { stage -> ownedStage = stage }
                    }
                }
            }
            delivered = result.isSuccess
            return result
        } finally {
            // withContext may discard a completed result on cancellation while
            // dispatching back to the UI. That result has not acquired an owner.
            if (!delivered) withContext(NonCancellable + Dispatchers.IO) { ownedStage?.let { safeDeleteStage(it.path) } }
        }
    }

    suspend fun loadContacts(): Result<NearbyContactPage> = withContext(Dispatchers.IO) {
        runCatching {
            require(
                ContextCompat.checkSelfPermission(application, android.Manifest.permission.READ_CONTACTS) ==
                    PackageManager.PERMISSION_GRANTED,
            ) { "Kontaktų leidimas nesuteiktas" }
            val projection = arrayOf(
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
            )
            val contacts = ArrayList<NearbyContact>(MAX_CONTACTS)
            var truncated = false
            application.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
            )?.use { cursor ->
                val lookupIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
                val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoIndex = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
                val seen = HashSet<String>()
                while (cursor.moveToNext()) {
                    coroutineContext.ensureActive()
                    val lookup = cursor.getString(lookupIndex)?.trim()?.takeIf(String::isNotBlank) ?: continue
                    if (!seen.add(lookup)) continue
                    if (contacts.size >= MAX_CONTACTS) {
                        truncated = true
                        break
                    }
                    // Keep missing names as data, not as Lithuanian UI copy. The composable renders
                    // its translated fallback while exported files use a stable neutral name.
                    val name = cursor.getString(nameIndex)?.trim().orEmpty()
                    contacts += NearbyContact(
                        lookupKey = lookup.take(1_024),
                        displayName = name.take(200),
                        photoUri = cursor.getString(photoIndex)?.takeIf(String::isNotBlank)?.take(2_048),
                    )
                }
            } ?: throw IllegalStateException("Kontaktų sąrašo perskaityti nepavyko")
            NearbyContactPage(contacts, truncated)
        }
    }

    suspend fun prepareContacts(contacts: Collection<NearbyContact>): Result<PreparedNearbyTransfer> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(
                    ContextCompat.checkSelfPermission(application, android.Manifest.permission.READ_CONTACTS) ==
                        PackageManager.PERMISSION_GRANTED,
                ) { "Kontaktų leidimas nesuteiktas" }
                val selected = contacts.distinctBy(NearbyContact::lookupKey)
                require(selected.isNotEmpty()) { "Pasirinkite bent vieną kontaktą" }
                require(selected.size <= MAX_CONTACTS) { "Vienu kartu galima siųsti iki $MAX_CONTACTS kontaktų" }
                val stage = newStage()
                try {
                    var batchBytes = 0L
                    val files = selected.mapIndexed { index, contact ->
                        coroutineContext.ensureActive()
                        require(
                            contact.lookupKey.isNotBlank() && contact.lookupKey.length <= 1_024 &&
                                contact.lookupKey.none(Char::isISOControl),
                        ) { "Kontakto duomenys netinkami" }
                        val baseName = contact.displayName
                            .replace(Regex("[\\p{Cc}\\p{Cf}/\\\\]"), "_")
                            .trim()
                            .take(180)
                            .ifBlank { "contact-${index + 1}" }
                        val target = uniqueFile(stage, "$baseName.vcf")
                        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, contact.lookupKey)
                        batchBytes = copyContentUri(uri, target, batchBytes)
                        target
                    }
                    validatedFiles(files, files.map(File::getName), emptyList(), stage)
                } catch (failure: Throwable) {
                    stage.deleteRecursively()
                    throw failure
                }
            }
        }

    private suspend fun copyContentUris(uris: List<Uri>, stage: File): PreparedNearbyTransfer {
        var batchBytes = 0L
        val files = uris.mapIndexed { index, uri ->
            coroutineContext.ensureActive()
            require(uri.scheme == "content") { "Palaikomos tik Android dokumentų nuorodos" }
            val target = uniqueFile(stage, contentName(uri, index))
            batchBytes = copyContentUri(uri, target, batchBytes)
            target
        }
        return validatedFiles(files, files.map(File::getName), emptyList(), stage)
    }

    /**
     * Keeps seekable Android documents at their provider. Unknown-length streams are the only
     * entries copied into a private stage because HTTP upload admission needs an exact length.
     */
    private suspend fun prepareDirectContentUris(
        uris: List<Uri>,
        onStageCreated: (File) -> Unit,
    ): PreparedNearbyTransfer {
        val paths = ArrayList<String>(uris.size)
        val sourceUris = ArrayList<String?>(uris.size)
        val relativePaths = ArrayList<String>(uris.size)
        val sizes = ArrayList<Long>(uris.size)
        val usedNames = HashSet<String>()
        var totalBytes = 0L
        var stage: File? = null
        try {
            uris.forEachIndexed { index, uri ->
                coroutineContext.ensureActive()
                require(uri.scheme == "content") { "Palaikomos tik Android dokumentų nuorodos" }
                val name = uniqueName(contentName(uri, index), usedNames)
                val knownSize = contentLength(uri)
                if (knownSize != null) {
                    require(knownSize in 0..LanHttpServer.MAX_UPLOAD_BYTES) { "Failas viršija saugyklos ribą" }
                    application.contentResolver.openInputStream(uri)?.use { Unit }
                        ?: throw IllegalArgumentException("Failo srautas nepasiekiamas: $name")
                    totalBytes = Math.addExact(totalBytes, knownSize)
                    require(totalBytes <= MAX_TOTAL_BYTES) { "Siuntimo rinkinys viršija 60 GB ribą" }
                    paths += ""
                    sourceUris += uri.toString()
                    relativePaths += name
                    sizes += knownSize
                } else {
                    val ownedStage = stage ?: newStage().also { created -> stage = created; onStageCreated(created) }
                    val target = uniqueFile(ownedStage, name)
                    totalBytes = copyContentUri(uri, target, totalBytes)
                    paths += target.canonicalPath
                    sourceUris += null
                    relativePaths += target.name
                    sizes += target.length()
                }
            }
            val payloadChars = paths.sumOf(String::length) + sourceUris.filterNotNull().sumOf(String::length) +
                relativePaths.sumOf(String::length)
            require(payloadChars <= MAX_PATH_PAYLOAD_CHARS) { "Siuntimo rinkinio kelių aprašas per didelis" }
            return PreparedNearbyTransfer(
                paths = paths,
                relativePaths = relativePaths.map(::normalizeRelative),
                cleanupRootPath = stage?.canonicalPath,
                fileSizes = sizes,
                totalBytes = totalBytes,
                sourceUris = sourceUris,
            )
        } catch (failure: Throwable) {
            stage?.deleteRecursively()
            throw failure
        }
    }

    private suspend fun copyContentUri(uri: Uri, target: File, batchBytesBefore: Long): Long {
        var batchBytes = batchBytesBefore
        application.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var total = 0L
                try {
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total = Math.addExact(total, read.toLong())
                        require(total <= LanHttpServer.MAX_UPLOAD_BYTES) { "Failas viršija saugyklos ribą" }
                        batchBytes = Math.addExact(batchBytes, read.toLong())
                        require(batchBytes <= MAX_TOTAL_BYTES) { "Siuntimo rinkinys viršija 60 GB ribą" }
                        output.write(buffer, 0, read)
                    }
                } finally { buffer.fill(0) }
            }
        } ?: throw IllegalArgumentException("Failo srautas nepasiekiamas")
        return batchBytes
    }

    private fun contentLength(uri: Uri): Long? {
        val descriptorSize = runCatching {
            application.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
        val statSize = runCatching {
            application.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it >= 0L }
            }
        }.getOrNull()
        var providerSize: Long? = null
        runCatching {
            application.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        }.getOrNull()?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { index -> providerSize = cursor.getLong(index).takeIf { it >= 0L } }
            }
        }
        // A seekable descriptor reflects the actual stream better than a provider's occasionally
        // stale metadata column, especially for large videos replaced in place.
        return descriptorSize ?: statSize ?: providerSize
    }

    suspend fun discard(prepared: PreparedNearbyTransfer) = withContext(Dispatchers.IO) {
        prepared.cleanupRootPath?.let(::safeDeleteStage)
    }

    suspend fun prepareLocalPaths(paths: Collection<String>): Result<PreparedNearbyTransfer> = withContext(Dispatchers.IO) {
        runCatching {
            val selected = paths.asSequence()
                // Keep the selected filesystem node unresolved until the symbolic-link check.
                // Canonicalizing first would silently turn a selected link into its target.
                .map { File(it).toPath().toAbsolutePath().normalize().toFile() }
                .filter(File::exists)
                .distinctBy(File::getAbsolutePath)
                .sortedBy { it.absolutePath.length }
                .toList()
            require(selected.isNotEmpty()) { "Pasirinkite bent vieną failą ar aplanką" }
            require(selected.size <= MAX_FILES) { "Vienu kartu galima pasirinkti iki $MAX_FILES pradinių elementų" }
            val compact = selected.filter { candidate ->
                selected.none { parent -> parent != candidate && parent.isDirectory && FileSystemRules.isContained(parent, candidate) }
            }
            prepareLocalNodes(compact)
        }
    }

    private suspend fun prepareLocalNodes(roots: List<File>): PreparedNearbyTransfer {
        val files = ArrayList<File>()
        val relativePaths = ArrayList<String>()
        val directories = LinkedHashSet<String>()
        val pending = ArrayDeque<Triple<File, String, Int>>()
        roots.asReversed().forEach { root -> pending.add(Triple(root, root.name, 0)) }
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (node, relative, depth) = pending.removeLast()
            require(depth <= MAX_DEPTH) { "Siunčiamo aplanko gylio riba viršyta" }
            require(!Files.isSymbolicLink(node.toPath())) { "Simbolinės nuorodos telefonu nesiunčiamos" }
            if (node.isDirectory) {
                require(node.canRead()) { "Aplankas nepasiekiamas: ${node.name}" }
                directories += normalizeRelative(relative)
                require(directories.size <= MAX_DIRECTORIES) { "Siunčiamame rinkinyje per daug aplankų" }
                val children = node.listFiles()?.sortedBy(File::getName)
                    ?: throw SecurityException("Aplankas neperskaitomas: ${node.name}")
                children.asReversed().forEach { child ->
                    require(FileSystemRules.isContained(node, child)) { "Aplanko elementas išeina už pasirinkto aplanko" }
                    pending.add(Triple(child, "$relative/${child.name}", depth + 1))
                }
            } else {
                require(node.isFile) { "Nepalaikomas elementas: ${node.name}" }
                files += node
                relativePaths += normalizeRelative(relative)
                require(files.size <= MAX_FILES) { "Siunčiamame rinkinyje daugiau nei $MAX_FILES failų" }
            }
        }
        require(files.isNotEmpty() || directories.isNotEmpty()) {
            "Pasirinktame rinkinyje nėra siunčiamų failų ar aplankų"
        }
        return validatedFiles(files, relativePaths, directories.toList(), cleanupRoot = null)
    }

    private fun validatedFiles(
        files: List<File>,
        relativePaths: List<String>,
        directories: List<String>,
        cleanupRoot: File?,
    ): PreparedNearbyTransfer {
        require(files.size == relativePaths.size) { "Siuntimo rinkinio keliai nesutampa" }
        require(files.size <= MAX_FILES && (files.isNotEmpty() || directories.isNotEmpty())) {
            "Netinkamas siunčiamų elementų skaičius"
        }
        var total = 0L
        val canonical = files.map { file ->
            val source = file.canonicalFile
            require(source.isFile && source.canRead()) { "Failas nepasiekiamas: ${file.name}" }
            require(source.length() in 0..LanHttpServer.MAX_UPLOAD_BYTES) { "Failas viršija saugyklos ribą" }
            total = Math.addExact(total, source.length())
            require(total <= MAX_TOTAL_BYTES) { "Siuntimo rinkinys viršija 60 GB ribą" }
            source.absolutePath
        }
        val normalizedRelative = relativePaths.map(::normalizeRelative)
        val normalizedDirectories = directories.map(::normalizeRelative).distinct()
        val payloadChars = canonical.sumOf(String::length) + normalizedRelative.sumOf(String::length) +
            normalizedDirectories.sumOf(String::length)
        require(payloadChars <= MAX_PATH_PAYLOAD_CHARS) { "Siuntimo rinkinio kelių aprašas per didelis" }
        return PreparedNearbyTransfer(
            paths = canonical,
            relativePaths = normalizedRelative,
            directories = normalizedDirectories,
            cleanupRootPath = cleanupRoot?.canonicalPath,
            fileSizes = files.map { it.length() },
            sourceUris = List(files.size) { null },
        )
    }

    private fun normalizeRelative(value: String): String {
        val parts = value.replace('\\', '/').split('/').filter(String::isNotBlank)
        require(parts.isNotEmpty() && parts.size <= MAX_DEPTH + 1) { "Netinkamas santykinis siuntimo kelias" }
        return parts.joinToString("/") { FileSystemRules.validateFileName(it).getOrThrow() }
    }

    private fun newStage(): File = File(stagingRoot, UUID.randomUUID().toString()).apply {
        require(parentFile?.let { it.isDirectory || it.mkdirs() } == true && mkdir()) {
            "Laikinos siuntimo vietos sukurti nepavyko"
        }
    }

    private fun safeDeleteStage(path: String) {
        val root = runCatching { stagingRoot.canonicalFile }.getOrNull() ?: return
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return
        if (candidate.parentFile == root) candidate.deleteRecursively()
    }

    private fun contentName(uri: Uri, index: Int): String {
        var displayName: String? = null
        runCatching {
            application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        }.getOrNull()?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { displayName = cursor.getString(it) }
            }
        }
        val cleaned = displayName.orEmpty()
            .replace(Regex("[\\p{Cc}\\p{Cf}/\\\\]"), "_")
            .trim()
            .take(200)
        return cleaned.ifBlank { "file-${index + 1}" }
    }

    private fun uniqueFile(parent: File, name: String): File {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        var candidate = File(parent, name)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(parent, "$stem ($suffix)$extension")
            suffix += 1
        }
        return candidate
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
        var candidate = name
        var suffix = 1
        while (!used.add(candidate.lowercase(java.util.Locale.ROOT))) {
            candidate = "$stem ($suffix)$extension"
            suffix += 1
        }
        return candidate
    }
}
