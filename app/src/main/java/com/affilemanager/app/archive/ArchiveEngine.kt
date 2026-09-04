package com.affilemanager.app.archive

import com.github.junrar.Archive
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Date
import java.util.UUID
import java.util.zip.ZipOutputStream

class ArchiveEngine(private val limits: ArchiveLimits = ArchiveLimits()) {
    companion object {
        private const val BUFFER_SIZE = 256 * 1_024
    }

    suspend fun list(archiveFile: File, password: CharArray? = null): List<ArchiveEntryInfo> = withContext(Dispatchers.IO) {
        require(archiveFile.isFile) { "Archyvas nepasiekiamas" }
        when (detectFormat(archiveFile)) {
            ArchiveFormat.ZIP -> listZip(archiveFile, password)
            ArchiveFormat.SEVEN_Z -> listSevenZ(archiveFile)
            ArchiveFormat.RAR -> listRar(archiveFile)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ -> listTar(archiveFile)
            ArchiveFormat.GZIP -> listOf(
                ArchiveEntryInfo(
                    name = archiveFile.name.removeSuffix(".${archiveFile.extension}"),
                    directory = false,
                    sizeBytes = -1,
                ),
            )
        }
    }

    suspend fun extract(
        archiveFile: File,
        destinationDirectory: File,
        password: CharArray? = null,
        operation: OperationContext? = null,
        fallbackExtractedName: String = "extracted",
    ) = withContext(Dispatchers.IO) {
        require(archiveFile.isFile) { "Archyvas nepasiekiamas" }
        require(destinationDirectory.isDirectory || destinationDirectory.mkdirs()) { "Paskirties aplankas nepasiekiamas" }
        when (detectFormat(archiveFile)) {
            ArchiveFormat.ZIP -> extractZip(archiveFile, destinationDirectory, password, operation)
            ArchiveFormat.SEVEN_Z -> extractSevenZ(archiveFile, destinationDirectory, operation)
            ArchiveFormat.RAR -> extractRar(archiveFile, destinationDirectory, operation)
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ -> extractTar(archiveFile, destinationDirectory, operation)
            ArchiveFormat.GZIP -> extractGzip(archiveFile, destinationDirectory, operation, fallbackExtractedName)
        }
    }

    /** Extracts one explicitly selected regular entry into an app-controlled file. */
    suspend fun extractEntry(
        archiveFile: File,
        entryPath: String,
        destinationFile: File,
        operation: OperationContext? = null,
        password: CharArray? = null,
    ) = withContext(Dispatchers.IO) {
        require(archiveFile.isFile) { "Archive is unavailable" }
        val requested = normalizedEntryPath(entryPath)
        require(destinationFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "Entry destination is unavailable"
        }
        when (detectFormat(archiveFile)) {
            ArchiveFormat.ZIP -> {
                val zip = if (password == null) ZipFile(archiveFile) else ZipFile(archiveFile, password)
                val header = zip.fileHeaders.takeChecked().firstOrNull {
                    normalizedEntryPath(it.fileName) == requested
                } ?: throw IllegalArgumentException("Archive entry was not found")
                require(!header.isDirectory) { "A folder cannot be materialized as a file" }
                require(header.uncompressedSize in 0..limits.maxSingleEntryBytes) { "Archive entry is too large" }
                zip.getInputStream(header).use { input ->
                    writeExtractedTarget(destinationFile) { output ->
                        copyBounded(input, output, header.uncompressedSize, 0, operation, requested)
                    }
                }
            }
            ArchiveFormat.SEVEN_Z -> {
                var found = false
                SevenZFile(archiveFile).use { archive ->
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        if (normalizedEntryPath(entry.name) != requested) continue
                        require(!entry.isDirectory) { "A folder cannot be materialized as a file" }
                        require(entry.size in 0..limits.maxSingleEntryBytes) { "Archive entry is too large" }
                        writeExtractedTarget(destinationFile) { output ->
                            copySevenZBounded(archive, output, entry.size, 0, operation, requested)
                        }
                        found = true
                        break
                    }
                }
                require(found) { "Archive entry was not found" }
            }
            ArchiveFormat.RAR -> {
                Archive(archiveFile).use { archive ->
                    val header = archive.fileHeaders.takeChecked().firstOrNull {
                        normalizedEntryPath(it.fileName) == requested
                    } ?: throw IllegalArgumentException("Archive entry was not found")
                    require(!header.isDirectory) { "A folder cannot be materialized as a file" }
                    require(header.fullUnpackSize in 0..limits.maxSingleEntryBytes) { "Archive entry is too large" }
                    writeExtractedTarget(destinationFile) { output ->
                        val bounded = BoundedOutputStream(output, limits.maxSingleEntryBytes) { written ->
                            operation?.progress(byteDelta = written, currentName = requested)
                        }
                        archive.extractFile(header, bounded)
                        bounded.flush()
                    }
                }
            }
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ -> {
                var found = false
                openTarInput(archiveFile).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (normalizedEntryPath(entry.name) != requested) continue
                        require(!entry.isDirectory && !entry.isSymbolicLink && !entry.isLink) {
                            "Only regular archive files can be materialized"
                        }
                        copyIntoExtractedTarget(input, destinationFile, entry.size, operation, requested)
                        found = true
                        break
                    }
                }
                require(found) { "Archive entry was not found" }
            }
            ArchiveFormat.GZIP -> {
                val name = archiveFile.name.removeSuffix(".${archiveFile.extension}")
                require(requested == normalizedEntryPath(name)) { "Archive entry was not found" }
                GzipCompressorInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { input ->
                    copyIntoExtractedTarget(input, destinationFile, -1, operation, requested)
                }
            }
        }
        require(destinationFile.isFile) { "Archive entry was not extracted" }
    }

    /** Extracts selected files or directory trees without overwriting any existing destination. */
    suspend fun extractEntries(
        archiveFile: File,
        selectedPaths: Collection<String>,
        destinationDirectory: File,
        operation: OperationContext? = null,
        password: CharArray? = null,
    ): Int = withContext(Dispatchers.IO) {
        require(archiveFile.isFile) { "Archyvas nepasiekiamas" }
        require(destinationDirectory.isDirectory || destinationDirectory.mkdirs()) { "Paskirties aplankas nepasiekiamas" }
        val selected = ArchiveMutationRules.normalizeSelection(selectedPaths).toHashSet()
        val entries = list(archiveFile, password)
        val chosenEntries = entries.filter { entry ->
            selectionContains(selected, ArchiveMutationRules.normalizePath(entry.name))
        }
        require(chosenEntries.isNotEmpty()) { "Pasirinktuose archyvo įrašuose nėra failų ar aplankų" }
        require(chosenEntries.size <= limits.maxEntries) { "Archyve per daug įrašų" }
        val chosenByPath = chosenEntries.associateBy { ArchiveMutationRules.normalizePath(it.name) }
        require(chosenByPath.size == chosenEntries.size) { "Pasirinktuose archyvo įrašuose kartojasi keliai" }
        enforceDeclaredLimits(chosenEntries.filterNot(ArchiveEntryInfo::directory).map(ArchiveEntryInfo::sizeBytes))
        operation?.setTotals(chosenEntries.size, chosenEntries.sumOf { if (it.directory) 0L else it.sizeBytes.coerceAtLeast(0L) })

        var processed = 0
        var expanded = 0L
        fun prepareTarget(path: String, directory: Boolean): File {
            val target = SafeArchivePath.resolve(destinationDirectory, path, limits.maxDepth)
            if (directory) {
                require(target.isDirectory || target.mkdirs()) { "Nepavyko sukurti $path" }
            } else {
                require(!target.exists()) { "Paskirties failas jau yra: $path" }
                target.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Nepavyko sukurti aplanko" } }
            }
            return target
        }
        suspend fun completed(path: String) {
            processed += 1
            operation?.progress(itemDelta = 1, currentName = path)
        }

        when (detectFormat(archiveFile)) {
            ArchiveFormat.ZIP -> {
                val zip = if (password == null) ZipFile(archiveFile) else ZipFile(archiveFile, password)
                zip.fileHeaders.takeChecked().forEach { header ->
                    val normalized = ArchiveMutationRules.normalizePath(header.fileName)
                    if (normalized !in chosenByPath) return@forEach
                    operation?.checkpoint()
                    val target = prepareTarget(normalized, header.isDirectory)
                    if (!header.isDirectory) {
                        zip.getInputStream(header).use { input ->
                            writeExtractedTarget(target) { output ->
                                expanded = copyBounded(input, output, header.uncompressedSize, expanded, operation, normalized)
                            }
                        }
                    }
                    completed(normalized)
                }
            }
            ArchiveFormat.SEVEN_Z -> SevenZFile(archiveFile).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val normalized = ArchiveMutationRules.normalizePath(entry.name)
                    if (normalized !in chosenByPath) continue
                    operation?.checkpoint()
                    val target = prepareTarget(normalized, entry.isDirectory)
                    if (!entry.isDirectory) {
                        writeExtractedTarget(target) { output ->
                            expanded = copySevenZBounded(archive, output, entry.size, expanded, operation, normalized)
                        }
                    }
                    completed(normalized)
                }
            }
            ArchiveFormat.RAR -> Archive(archiveFile).use { archive ->
                archive.fileHeaders.takeChecked().forEach { header ->
                    val normalized = ArchiveMutationRules.normalizePath(header.fileName)
                    if (normalized !in chosenByPath) return@forEach
                    operation?.checkpoint()
                    val target = prepareTarget(normalized, header.isDirectory)
                    if (!header.isDirectory) {
                        writeExtractedTarget(target) { output ->
                            val bounded = BoundedOutputStream(output, limits.maxSingleEntryBytes) { written ->
                                expanded = Math.addExact(expanded, written)
                                require(expanded <= limits.maxExpandedBytes) { "Archyvo išplėtimo riba viršyta" }
                                operation?.progress(byteDelta = written, currentName = normalized)
                            }
                            archive.extractFile(header, bounded)
                        }
                    }
                    completed(normalized)
                }
            }
            ArchiveFormat.TAR, ArchiveFormat.TAR_GZ -> openTarInput(archiveFile).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val normalized = ArchiveMutationRules.normalizePath(entry.name)
                    if (normalized !in chosenByPath) continue
                    require(!entry.isSymbolicLink && !entry.isLink) { "Archyvo nuorodos saugumo sumetimais neišpakuojamos" }
                    operation?.checkpoint()
                    val target = prepareTarget(normalized, entry.isDirectory)
                    if (!entry.isDirectory) {
                        writeExtractedTarget(target) { output ->
                            expanded = copyBounded(input, output, entry.size, expanded, operation, normalized)
                        }
                    }
                    completed(normalized)
                }
            }
            ArchiveFormat.GZIP -> {
                val entry = chosenEntries.single()
                val normalized = ArchiveMutationRules.normalizePath(entry.name)
                operation?.checkpoint()
                val target = prepareTarget(normalized, directory = false)
                GzipCompressorInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { input ->
                    writeExtractedTarget(target) { output ->
                        expanded = copyBounded(input, output, -1, expanded, operation, normalized)
                    }
                }
                completed(normalized)
            }
        }
        require(processed == chosenEntries.size) { "Pasirinktų archyvo įrašų išpakuoti nepavyko" }
        processed
    }

    /** Transactionally removes selected entries from a writable .zip archive. */
    suspend fun deleteZipEntries(
        archiveFile: File,
        selectedPaths: Collection<String>,
        password: CharArray? = null,
    ): List<ArchiveEntryInfo> = rewriteZip(archiveFile, password) { zip, before ->
        val headers = ArchiveMutationRules.deletionHeaders(before, selectedPaths)
        zip.removeFiles(headers)
        val selected = ArchiveMutationRules.normalizeSelection(selectedPaths)
        val verifier: (List<ArchiveEntryInfo>) -> Boolean = { after ->
            after.none { entry ->
                val normalized = ArchiveMutationRules.normalizePath(entry.name)
                selected.any { normalized == it || normalized.startsWith("$it/") }
            }
        }
        verifier
    }

    /** Transactionally renames one file or directory tree in a writable .zip archive. */
    suspend fun renameZipEntry(
        archiveFile: File,
        sourcePath: String,
        requestedName: String,
        password: CharArray? = null,
    ): List<ArchiveEntryInfo> = rewriteZip(archiveFile, password) { zip, before ->
        val plan = ArchiveMutationRules.renamePlan(before, sourcePath, requestedName)
        zip.renameFiles(plan.renamedHeaders)
        val verifier: (List<ArchiveEntryInfo>) -> Boolean = { after ->
            val normalized = after.map { ArchiveMutationRules.normalizePath(it.name) }.toSet()
            plan.renamedHeaders.values.all { ArchiveMutationRules.normalizePath(it) in normalized } &&
                normalized.none { it == plan.sourcePath || it.startsWith("${plan.sourcePath}/") }
        }
        verifier
    }

    suspend fun create(
        format: ArchiveFormat,
        outputFile: File,
        sources: List<File>,
        password: CharArray? = null,
        operation: OperationContext? = null,
    ) = withContext(Dispatchers.IO) {
        var partial: File? = null
        try {
            require(sources.all(File::exists)) { "Kai kurie šaltiniai nebeegzistuoja" }
            require(format != ArchiveFormat.RAR && format != ArchiveFormat.GZIP) { "Šį formatą galima tik išpakuoti" }
            require(password == null || format == ArchiveFormat.ZIP) { "Šifravimas palaikomas kuriant ZIP" }
            require(sources.isNotEmpty() || password == null) { "Tuščias archyvas negali būti užšifruotas" }
            require(!outputFile.exists()) { "Toks archyvas jau egzistuoja" }
            val parent = outputFile.parentFile
            require(parent != null && (parent.isDirectory || parent.mkdirs())) { "Archyvo aplankas nepasiekiamas" }
            validateCreateSources(sources, outputFile, operation)
            val partialFile = File(parent, ".${outputFile.name}.partial")
            partial = partialFile
            require(!partialFile.exists()) { "Laikinas archyvo failas jau egzistuoja" }
            when (format) {
                ArchiveFormat.ZIP -> if (sources.isEmpty()) createEmptyZip(partialFile) else createZip(partialFile, sources, password, operation)
                ArchiveFormat.SEVEN_Z -> createSevenZ(partialFile, sources, operation)
                ArchiveFormat.TAR -> createTar(partialFile, sources, gzip = false, operation)
                ArchiveFormat.TAR_GZ -> createTar(partialFile, sources, gzip = true, operation)
                ArchiveFormat.RAR, ArchiveFormat.GZIP -> error("Nepalaikomas kūrimo formatas")
            }
            require(partialFile.isFile && partialFile.length() > 0) { "Archyvas nesukurtas" }
            require(!outputFile.exists()) { "Toks archyvas jau egzistuoja" }
            require(partialFile.renameTo(outputFile)) { "Archyvo užbaigti nepavyko" }
        } finally {
            password?.fill('\u0000')
            if (partial?.exists() == true) partial.delete()
        }
    }

    private data class CreateSourceScan(
        var entries: Int = 0,
        var bytes: Long = 0L,
        val canonicalPaths: MutableSet<String> = hashSetOf(),
    )

    private suspend fun validateCreateSources(
        sources: List<File>,
        outputFile: File,
        operation: OperationContext?,
    ) {
        val output = outputFile.canonicalFile
        val scan = CreateSourceScan()
        sources.forEach { source ->
            val canonical = source.canonicalFile
            require(output != canonical && !output.path.startsWith(canonical.path + File.separator)) {
                "Archyvo negalima kurti archyvuojamo aplanko viduje"
            }
            scanCreateSource(source, depth = 0, scan, operation)
        }
        operation?.setTotals(scan.entries, scan.bytes)
    }

    private suspend fun scanCreateSource(
        source: File,
        depth: Int,
        scan: CreateSourceScan,
        operation: OperationContext?,
    ) {
        operation?.checkpoint()
        require(depth <= limits.maxDepth) { "Per gilus aplankų medis" }
        require(!Files.isSymbolicLink(source.toPath())) { "Simbolinės nuorodos į archyvą neįtraukiamos" }
        val canonical = source.canonicalFile
        require(scan.canonicalPaths.add(canonical.path)) { "Tas pats archyvo šaltinis pasirinktas kelis kartus" }
        require(canonical.isFile || canonical.isDirectory) { "Archyvo šaltinio tipas nepalaikomas" }
        scan.entries = Math.addExact(scan.entries, 1)
        require(scan.entries <= limits.maxEntries) { "Pasirinkta per daug archyvo įrašų" }
        if (canonical.isFile) {
            val size = canonical.length().coerceAtLeast(0L)
            require(size <= limits.maxSingleEntryBytes) { "Archyvo šaltinio failas per didelis" }
            scan.bytes = Math.addExact(scan.bytes, size)
            require(scan.bytes <= limits.maxExpandedBytes) { "Archyvo šaltiniai viršija dydžio ribą" }
            return
        }
        val children = canonical.listFiles() ?: throw SecurityException("Aplankas neperskaitomas")
        children.forEach { child -> scanCreateSource(child, depth + 1, scan, operation) }
    }

    fun detectFormat(file: File): ArchiveFormat {
        val lower = file.name.lowercase()
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            lower.endsWith(".tar") -> ArchiveFormat.TAR
            lower.endsWith(".7z") -> ArchiveFormat.SEVEN_Z
            lower.endsWith(".rar") -> ArchiveFormat.RAR
            lower.endsWith(".gz") -> ArchiveFormat.GZIP
            lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".apk") -> ArchiveFormat.ZIP
            else -> throw IllegalArgumentException("Nepalaikomas archyvo formatas")
        }
    }

    private fun listZip(file: File, password: CharArray?): List<ArchiveEntryInfo> {
        val zip = if (password == null) ZipFile(file) else ZipFile(file, password)
        return zip.fileHeaders.takeChecked().map { header ->
            ArchiveEntryInfo(
                name = header.fileName,
                directory = header.isDirectory,
                sizeBytes = header.uncompressedSize,
                compressedSizeBytes = header.compressedSize,
                modifiedAtMillis = header.lastModifiedTimeEpoch,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun listSevenZ(file: File): List<ArchiveEntryInfo> {
        val result = mutableListOf<ArchiveEntryInfo>()
        SevenZFile(file).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                require(result.size < limits.maxEntries) { "Archyve per daug įrašų" }
                result += ArchiveEntryInfo(
                    name = entry.name,
                    directory = entry.isDirectory,
                    sizeBytes = entry.size,
                    modifiedAtMillis = entry.lastModifiedDate?.time,
                )
            }
        }
        return result
    }

    private fun listRar(file: File): List<ArchiveEntryInfo> = Archive(file).use { archive ->
        archive.fileHeaders.takeChecked().map { header ->
            ArchiveEntryInfo(
                name = header.fileName,
                directory = header.isDirectory,
                sizeBytes = header.fullUnpackSize,
                compressedSizeBytes = header.fullPackSize,
                modifiedAtMillis = header.mTime?.time,
            )
        }
    }

    private fun listTar(file: File): List<ArchiveEntryInfo> {
        val result = mutableListOf<ArchiveEntryInfo>()
        openTarInput(file).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                require(result.size < limits.maxEntries) { "Archyve per daug įrašų" }
                result += ArchiveEntryInfo(
                    name = entry.name,
                    directory = entry.isDirectory,
                    sizeBytes = entry.size,
                    modifiedAtMillis = entry.lastModifiedDate.time,
                )
            }
        }
        return result
    }

    private suspend fun extractZip(file: File, destination: File, password: CharArray?, operation: OperationContext?) {
        val zip = if (password == null) ZipFile(file) else ZipFile(file, password)
        val headers = zip.fileHeaders.takeChecked()
        enforceDeclaredLimits(headers.map { it.uncompressedSize })
        operation?.setTotals(headers.size, headers.sumOf { it.uncompressedSize.coerceAtLeast(0) })
        var expanded = 0L
        headers.forEach { header ->
            operation?.checkpoint()
            val target = SafeArchivePath.resolve(destination, header.fileName, limits.maxDepth)
            if (header.isDirectory) {
                require(target.isDirectory || target.mkdirs()) { "Nepavyko sukurti ${header.fileName}" }
            } else {
                target.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Nepavyko sukurti aplanko" } }
                zip.getInputStream(header).use { input ->
                    writeExtractedTarget(target) { output ->
                        expanded = copyBounded(input, output, header.uncompressedSize, expanded, operation, header.fileName)
                    }
                }
            }
            operation?.progress(itemDelta = 1, currentName = header.fileName)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun extractSevenZ(file: File, destination: File, operation: OperationContext?) {
        var entries = 0
        var expanded = 0L
        SevenZFile(file).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                entries += 1
                require(entries <= limits.maxEntries) { "Archyve per daug įrašų" }
                require(entry.size <= limits.maxSingleEntryBytes) { "Archyvo įrašas per didelis" }
                operation?.checkpoint()
                val target = SafeArchivePath.resolve(destination, entry.name, limits.maxDepth)
                if (entry.isDirectory) {
                    require(target.isDirectory || target.mkdirs()) { "Nepavyko sukurti ${entry.name}" }
                } else {
                    target.parentFile?.mkdirs()
                    writeExtractedTarget(target) { output ->
                        expanded = copySevenZBounded(archive, output, entry.size, expanded, operation, entry.name)
                    }
                }
                operation?.progress(itemDelta = 1, currentName = entry.name)
            }
        }
    }

    private suspend fun extractRar(file: File, destination: File, operation: OperationContext?) {
        Archive(file).use { archive ->
            val headers = archive.fileHeaders.takeChecked()
            enforceDeclaredLimits(headers.map { it.fullUnpackSize })
            operation?.setTotals(headers.size, headers.sumOf { it.fullUnpackSize.coerceAtLeast(0) })
            var expanded = 0L
            headers.forEach { header ->
                operation?.checkpoint()
                val target = SafeArchivePath.resolve(destination, header.fileName, limits.maxDepth)
                if (header.isDirectory) {
                    require(target.isDirectory || target.mkdirs()) { "Nepavyko sukurti ${header.fileName}" }
                } else {
                    target.parentFile?.mkdirs()
                    writeExtractedTarget(target) { output ->
                        val bounded = BoundedOutputStream(output, limits.maxSingleEntryBytes) { written ->
                            expanded = Math.addExact(expanded, written)
                            require(expanded <= limits.maxExpandedBytes) { "Archyvo išplėtimo riba viršyta" }
                            operation?.progress(byteDelta = written, currentName = header.fileName)
                        }
                        archive.extractFile(header, bounded)
                    }
                }
                operation?.progress(itemDelta = 1, currentName = header.fileName)
            }
        }
    }

    private suspend fun extractTar(file: File, destination: File, operation: OperationContext?) {
        var entries = 0
        var expanded = 0L
        openTarInput(file).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entries += 1
                require(entries <= limits.maxEntries) { "Archyve per daug įrašų" }
                require(!entry.isSymbolicLink && !entry.isLink) { "Archyvo nuorodos saugumo sumetimais neišpakuojamos" }
                operation?.checkpoint()
                val target = SafeArchivePath.resolve(destination, entry.name, limits.maxDepth)
                if (entry.isDirectory) {
                    require(target.isDirectory || target.mkdirs()) { "Nepavyko sukurti ${entry.name}" }
                } else {
                    target.parentFile?.mkdirs()
                    writeExtractedTarget(target) { output ->
                        expanded = copyBounded(input, output, entry.size, expanded, operation, entry.name)
                    }
                }
                operation?.progress(itemDelta = 1, currentName = entry.name)
            }
        }
    }

    private suspend fun extractGzip(
        file: File,
        destination: File,
        operation: OperationContext?,
        fallbackExtractedName: String,
    ) {
        val name = file.name.removeSuffix(".${file.extension}").ifBlank {
            fallbackExtractedName.ifBlank { "extracted" }
        }
        val target = SafeArchivePath.resolve(destination, name, limits.maxDepth)
        GzipCompressorInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            writeExtractedTarget(target) { output ->
                copyBounded(input, output, -1, 0, operation, name)
            }
        }
        operation?.progress(itemDelta = 1, currentName = name)
    }

    private suspend fun createZip(file: File, sources: List<File>, password: CharArray?, operation: OperationContext?) {
        val zip = if (password == null) ZipFile(file) else ZipFile(file, password)
        val parameters = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            compressionLevel = CompressionLevel.NORMAL
            if (password != null) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        sources.forEach { source ->
            operation?.checkpoint()
            if (source.isDirectory) zip.addFolder(source, parameters) else zip.addFile(source, parameters)
            operation?.progress(itemDelta = 1, byteDelta = source.length(), currentName = source.name)
        }
    }

    private suspend fun rewriteZip(
        archiveFile: File,
        password: CharArray?,
        mutate: (ZipFile, List<ArchiveEntryInfo>) -> (List<ArchiveEntryInfo>) -> Boolean,
    ): List<ArchiveEntryInfo> = withContext(Dispatchers.IO) {
        val original = archiveFile.canonicalFile
        require(original.isFile && original.canRead() && original.canWrite()) { "Archyvo negalima pakeisti" }
        require(original.name.lowercase().endsWith(".zip")) { "Keisti galima tik ZIP archyvus" }
        val parent = original.parentFile ?: throw IllegalArgumentException("Archyvo aplankas nepasiekiamas")
        require(parent.isDirectory && parent.canWrite()) { "Archyvo aplankas neleidžia rašyti" }
        val token = UUID.randomUUID().toString()
        val working = File(parent, ".${original.name}.$token.af-rewrite")
        val backup = File(parent, ".${original.name}.$token.af-recovery")
        var replacementStarted = false
        try {
            Files.copy(original.toPath(), working.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            require(working.length() == original.length()) { "Laikina archyvo kopija nepatikrinta" }
            Files.copy(original.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            require(backup.length() == original.length()) { "Archyvo atkūrimo kopija nepatikrinta" }

            val before = listZip(working, password)
            val zip = if (password == null) ZipFile(working) else ZipFile(working, password)
            require(zip.isValidZipFile) { "Laikina ZIP kopija sugadinta" }
            val verify = mutate(zip, before)
            require(zip.isValidZipFile) { "Pakeista ZIP kopija sugadinta" }
            val expected = listZip(working, password)
            require(verify(expected)) { "Archyvo pakeitimo patikra nepavyko" }

            replacementStarted = true
            moveReplacing(working, original)
            val actual = listZip(original, password)
            require(verify(actual)) { "Įrašyto archyvo patikra nepavyko" }
            require(backup.delete()) { "Archyvas pakeistas, bet laikinos atkūrimo kopijos pašalinti nepavyko" }
            actual
        } catch (failure: Throwable) {
            if (replacementStarted && backup.isFile) {
                val restore = File(parent, ".${original.name}.$token.af-restore")
                runCatching {
                    Files.copy(backup.toPath(), restore.toPath())
                    require(restore.length() == backup.length()) { "Archyvo atkūrimo kopija nepatikrinta" }
                    moveReplacing(restore, original)
                    require(ZipFile(original).isValidZipFile) { "Atkurto archyvo patikra nepavyko" }
                    backup.delete()
                }.onFailure { restoreFailure ->
                    failure.addSuppressed(restoreFailure)
                }
                if (restore.exists()) restore.delete()
            }
            throw failure
        } finally {
            password?.fill('\u0000')
            if (working.exists()) working.delete()
            if (!replacementStarted && backup.exists()) backup.delete()
        }
    }

    private fun moveReplacing(source: File, destination: File) {
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun createEmptyZip(file: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { output ->
            output.finish()
        }
    }

    private suspend fun createSevenZ(file: File, sources: List<File>, operation: OperationContext?) {
        SevenZOutputFile(file).use { output ->
            sources.forEach { source -> addToSevenZ(output, source, source.name, operation, 0) }
        }
    }

    private suspend fun addToSevenZ(
        output: SevenZOutputFile,
        source: File,
        archiveName: String,
        operation: OperationContext?,
        depth: Int,
    ) {
        require(depth <= limits.maxDepth) { "Per gilus aplankų medis" }
        operation?.checkpoint()
        val entry = output.createArchiveEntry(source, archiveName)
        output.putArchiveEntry(entry)
        if (source.isFile) {
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    operation?.checkpoint()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
        output.closeArchiveEntry()
        operation?.progress(itemDelta = 1, byteDelta = source.length(), currentName = source.name)
        if (source.isDirectory) {
            source.listFiles()?.forEach { child -> addToSevenZ(output, child, "$archiveName/${child.name}", operation, depth + 1) }
                ?: throw SecurityException("Aplankas neperskaitomas")
        }
    }

    private suspend fun createTar(
        file: File,
        sources: List<File>,
        gzip: Boolean,
        operation: OperationContext?,
    ) {
        val fileOutput = BufferedOutputStream(FileOutputStream(file))
        val compressed: OutputStream = if (gzip) GzipCompressorOutputStream(fileOutput) else fileOutput
        TarArchiveOutputStream(compressed).use { output ->
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            sources.forEach { source -> addToTar(output, source, source.name, operation, 0) }
        }
    }

    private suspend fun addToTar(
        output: TarArchiveOutputStream,
        source: File,
        archiveName: String,
        operation: OperationContext?,
        depth: Int,
    ) {
        require(depth <= limits.maxDepth) { "Per gilus aplankų medis" }
        operation?.checkpoint()
        val entry = TarArchiveEntry(source, archiveName)
        output.putArchiveEntry(entry)
        if (source.isFile) source.inputStream().buffered().use { it.copyTo(output, BUFFER_SIZE) }
        output.closeArchiveEntry()
        operation?.progress(itemDelta = 1, byteDelta = source.length(), currentName = source.name)
        if (source.isDirectory) {
            source.listFiles()?.forEach { child -> addToTar(output, child, "$archiveName/${child.name}", operation, depth + 1) }
                ?: throw SecurityException("Aplankas neperskaitomas")
        }
    }

    private fun openTarInput(file: File): TarArchiveInputStream {
        val base = BufferedInputStream(FileInputStream(file))
        val input: InputStream = if (file.name.lowercase().endsWith(".gz") || file.name.lowercase().endsWith(".tgz")) {
            GzipCompressorInputStream(base)
        } else {
            base
        }
        return TarArchiveInputStream(input)
    }

    private suspend fun copyIntoExtractedTarget(
        input: InputStream,
        destination: File,
        declaredSize: Long,
        operation: OperationContext?,
        name: String,
    ) {
        writeExtractedTarget(destination) { output ->
            copyBounded(input, output, declaredSize, 0, operation, name)
        }
    }

    private fun normalizedEntryPath(raw: String): String {
        val normalized = raw.replace('\\', '/').trimStart('/').trimEnd('/')
        require(normalized.isNotBlank() && '\u0000' !in normalized) { "Invalid archive entry path" }
        val pieces = normalized.split('/')
        require(pieces.size <= limits.maxDepth && pieces.none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe archive entry path"
        }
        return pieces.joinToString("/")
    }

    private fun selectionContains(selectedPaths: Set<String>, path: String): Boolean {
        var candidate = path
        while (true) {
            if (candidate in selectedPaths) return true
            val separator = candidate.lastIndexOf('/')
            if (separator < 0) return false
            candidate = candidate.substring(0, separator)
        }
    }

    private suspend fun copyBounded(
        input: InputStream,
        output: OutputStream,
        declaredSize: Long,
        expandedBefore: Long,
        operation: OperationContext?,
        name: String,
    ): Long {
        if (declaredSize >= 0) require(declaredSize <= limits.maxSingleEntryBytes) { "Archyvo įrašas per didelis" }
        var entryBytes = 0L
        var expanded = expandedBefore
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            operation?.checkpoint()
            val read = input.read(buffer)
            if (read < 0) break
            entryBytes = Math.addExact(entryBytes, read.toLong())
            expanded = Math.addExact(expanded, read.toLong())
            require(entryBytes <= limits.maxSingleEntryBytes) { "Archyvo įrašas viršijo ribą" }
            require(expanded <= limits.maxExpandedBytes) { "Archyvo išplėtimo riba viršyta" }
            output.write(buffer, 0, read)
            operation?.progress(byteDelta = read.toLong(), currentName = name)
        }
        return expanded
    }

    private suspend fun copySevenZBounded(
        archive: SevenZFile,
        output: OutputStream,
        declaredSize: Long,
        expandedBefore: Long,
        operation: OperationContext?,
        name: String,
    ): Long {
        require(declaredSize <= limits.maxSingleEntryBytes) { "Archyvo įrašas per didelis" }
        var entryBytes = 0L
        var expanded = expandedBefore
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            operation?.checkpoint()
            val read = archive.read(buffer)
            if (read < 0) break
            entryBytes = Math.addExact(entryBytes, read.toLong())
            expanded = Math.addExact(expanded, read.toLong())
            require(entryBytes <= limits.maxSingleEntryBytes && expanded <= limits.maxExpandedBytes) { "Archyvo riba viršyta" }
            output.write(buffer, 0, read)
            operation?.progress(byteDelta = read.toLong(), currentName = name)
        }
        return expanded
    }

    private suspend fun writeExtractedTarget(target: File, block: suspend (OutputStream) -> Unit) {
        target.parentFile?.let { require(it.isDirectory || it.mkdirs()) { "Nepavyko sukurti paskirties aplanko" } }
        val partial = File(target.parentFile, ".${target.name}.af-partial")
        try {
            BufferedOutputStream(FileOutputStream(partial)).use { output -> block(output) }
            runCatching {
                Files.move(
                    partial.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private fun enforceDeclaredLimits(sizes: List<Long>) {
        require(sizes.size <= limits.maxEntries) { "Archyve per daug įrašų" }
        var total = 0L
        sizes.forEach { size ->
            require(size < 0 || size <= limits.maxSingleEntryBytes) { "Archyvo įrašas per didelis" }
            if (size > 0) total = Math.addExact(total, size)
            require(total <= limits.maxExpandedBytes) { "Archyvas išsiplečia per daug" }
        }
    }

    private fun <T> List<T>.takeChecked(): List<T> {
        require(size <= limits.maxEntries) { "Archyve per daug įrašų" }
        return this
    }
}

private class BoundedOutputStream(
    private val delegate: OutputStream,
    private val maxBytes: Long,
    private val onWrite: (Long) -> Unit,
) : OutputStream() {
    private var written = 0L

    override fun write(value: Int) {
        require(written < maxBytes) { "Archyvo įrašas viršijo ribą" }
        delegate.write(value)
        written += 1
        onWrite(1)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(written + length <= maxBytes) { "Archyvo įrašas viršijo ribą" }
        delegate.write(buffer, offset, length)
        written += length
        onWrite(length.toLong())
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
