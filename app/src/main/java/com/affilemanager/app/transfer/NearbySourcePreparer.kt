package com.affilemanager.app.transfer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.FileCategoryRepository
import com.affilemanager.app.model.FileEntry
import kotlinx.coroutines.Dispatchers
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
)

class NearbySourcePreparer(
    private val application: Application,
    private val fileCategories: FileCategoryRepository,
) {
    companion object {
        const val MAX_FILES = 1_000
        const val MAX_DIRECTORIES = 2_000
        const val MAX_TOTAL_BYTES = 5L * 1_024L * 1_024L * 1_024L
        const val MAX_PATH_PAYLOAD_CHARS = 250_000
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

    suspend fun prepareContentUris(uris: Collection<Uri>): Result<PreparedNearbyTransfer> = withContext(Dispatchers.IO) {
        runCatching {
            val selected = uris.distinctBy(Uri::toString)
            require(selected.isNotEmpty()) { "Pasirinkite bent vieną failą" }
            require(selected.size <= MAX_FILES) { "Vienu kartu galima siųsti iki $MAX_FILES failų" }
            val stage = newStage()
            try {
                val files = selected.mapIndexed { index, uri ->
                    coroutineContext.ensureActive()
                    require(uri.scheme == "content") { "Palaikomos tik Android dokumentų nuorodos" }
                    val name = contentName(uri, index)
                    val target = uniqueFile(stage, name)
                    application.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                total = Math.addExact(total, read.toLong())
                                require(total <= LanHttpServer.MAX_UPLOAD_BYTES) { "Failas viršija 1 GB ribą" }
                                output.write(buffer, 0, read)
                            }
                            buffer.fill(0)
                        }
                    } ?: throw IllegalArgumentException("Failo srautas nepasiekiamas")
                    target
                }
                validatedFiles(files, files.map(File::getName), emptyList(), stage)
            } catch (error: Throwable) {
                stage.deleteRecursively()
                throw error
            }
        }
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
            require(source.length() in 0..LanHttpServer.MAX_UPLOAD_BYTES) { "Failas viršija 1 GB ribą: ${file.name}" }
            total = Math.addExact(total, source.length())
            require(total <= MAX_TOTAL_BYTES) { "Siuntimo rinkinys viršija 5 GB ribą" }
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
}
