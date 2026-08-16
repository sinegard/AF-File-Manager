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

    suspend fun create(
        format: ArchiveFormat,
        outputFile: File,
        sources: List<File>,
        password: CharArray? = null,
        operation: OperationContext? = null,
    ) = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty()) { "Nepasirinkta failų" }
        require(sources.all(File::exists)) { "Kai kurie šaltiniai nebeegzistuoja" }
        require(format != ArchiveFormat.RAR && format != ArchiveFormat.GZIP) { "Šį formatą galima tik išpakuoti" }
        require(password == null || format == ArchiveFormat.ZIP) { "Šifravimas palaikomas kuriant ZIP" }
        outputFile.parentFile?.mkdirs()
        val partial = File(outputFile.parentFile, ".${outputFile.name}.partial")
        try {
            when (format) {
                ArchiveFormat.ZIP -> createZip(partial, sources, password, operation)
                ArchiveFormat.SEVEN_Z -> createSevenZ(partial, sources, operation)
                ArchiveFormat.TAR -> createTar(partial, sources, gzip = false, operation)
                ArchiveFormat.TAR_GZ -> createTar(partial, sources, gzip = true, operation)
                ArchiveFormat.RAR, ArchiveFormat.GZIP -> error("Nepalaikomas kūrimo formatas")
            }
            require(partial.isFile && partial.length() > 0) { "Archyvas nesukurtas" }
            if (outputFile.exists()) require(outputFile.delete()) { "Esamo archyvo pakeisti nepavyko" }
            require(partial.renameTo(outputFile)) { "Archyvo užbaigti nepavyko" }
        } finally {
            password?.fill('\u0000')
            if (partial.exists()) partial.delete()
        }
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
