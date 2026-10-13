package com.affilemanager.app.thumbnail

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

internal object ThumbnailSources {
    fun local(path: String): String = "local|${localPath(path)}"
    fun document(uri: String): String = "document|$uri"

    /** Resolve Android aliases such as /data/user/0 and /data/data off the UI thread. */
    fun localPath(path: String): String {
        val file = File(path).absoluteFile
        return try {
            file.canonicalPath
        } catch (_: IOException) {
            file.toPath().normalize().toString()
        } catch (_: SecurityException) {
            file.toPath().normalize().toString()
        }
    }

    fun localPaths(paths: Collection<String>): Set<String> = buildSet {
        paths.forEach {
            // Also match an exact lexical identity if an earlier resolution was unavailable.
            add(File(it).absoluteFile.toPath().normalize().toString())
            add(localPath(it))
        }
    }

    fun affectedLocal(source: String, paths: Set<String>): Boolean {
        if (!source.startsWith("local|")) return false
        var path = source.removePrefix("local|")
        repeat(256) {
            if (path in paths) return true
            val separator = path.lastIndexOf(File.separatorChar)
            if (separator < 0 || path == File.separator) return false
            path = if (separator == 0) File.separator else path.substring(0, separator)
        }
        return false
    }

    fun documentAuthorities(uris: Collection<String>): Set<String> = uris.mapNotNullTo(hashSetOf()) {
        runCatching { URI(it).rawAuthority }.getOrNull()
    }

    fun affectedDocument(source: String, authorities: Set<String>): Boolean =
        source.startsWith("document|") &&
            runCatching { URI(source.removePrefix("document|")).rawAuthority in authorities }.getOrDefault(false)
}

/** Reconstructible app-private data only. Call exclusively on a background thread. */
internal class ThumbnailDiskCache(
    val directory: File,
    private val maximumBytes: Long = MAX_BYTES,
    private val maximumEntries: Int = MAX_ENTRIES,
) {
    companion object {
        const val MAX_BYTES = 128L * 1_024L * 1_024L
        const val MAX_ENTRIES = 2_048
        const val MAX_PAYLOAD_BYTES = 2 * 1_024 * 1_024
        const val MAX_INDEX_BYTES = 4L * 1_024L * 1_024L
        private const val MAX_STRING_BYTES = 8_192
        private const val MAX_SCAN_FILES = 4_096
        private const val MAGIC = 0x41465431 // AF thumbnail format 1; not a class name.
        private val entryName = Regex("[0-9a-f]{64}\\.thumb")
        private val partialName = Regex("[0-9a-f]{64}-[0-9a-f-]{36}\\.pending")
    }

    data class Record(val bytes: ByteArray, val crop: Boolean, val contentThumbnail: Boolean)
    data class Snapshot(val entries: Int, val bytes: Long, val corruptEntries: Long, val indexBytes: Long)
    private data class Header(
        val key: String,
        val source: String,
        val revision: String,
        val crop: Boolean,
        val contentThumbnail: Boolean,
        val payloadSize: Int,
        val checksum: Long,
    )
    private data class Entry(val file: File, val header: Header, val bytes: Long)

    private val entries = LinkedHashMap<String, Entry>(32, 0.75f, true)
    private val keysBySource = hashMapOf<String, MutableSet<String>>()
    private var initialized = false
    private var bytes = 0L
    private var indexBytes = 0L
    private var corruptEntries = 0L
    val corruptionCount: Long get() = synchronized(this) { corruptEntries }

    init {
        require(maximumBytes in 1..MAX_BYTES && maximumEntries in 1..MAX_ENTRIES)
    }

    @Synchronized
    fun read(key: String, source: String, revision: String): Record? {
        initialize()
        val entry = entries[key] ?: return null
        if (entry.header.source != source || entry.header.revision != revision) {
            remove(key)
            return null
        }
        return try {
            requireRegularFile(entry.file)
            DataInputStream(BufferedInputStream(entry.file.inputStream())).use { input ->
                val header = readHeader(input)
                check(header == entry.header) { "AF-THUMB-HEADER" }
                val payload = ByteArray(header.payloadSize)
                input.readFully(payload)
                check(input.read() == -1 && CRC32().apply { update(payload) }.value == header.checksum) {
                    "AF-THUMB-CHECKSUM"
                }
                val now = System.currentTimeMillis()
                if (now - entry.file.lastModified() >= 60_000L) entry.file.setLastModified(now)
                Record(payload, header.crop, header.contentThumbnail)
            }
        } catch (error: IOException) {
            corruptEntries++
            remove(key)
            null
        } catch (error: IllegalStateException) {
            corruptEntries++
            remove(key)
            null
        } catch (error: IllegalArgumentException) {
            corruptEntries++
            remove(key)
            null
        }
    }

    @Synchronized
    fun write(key: String, source: String, revision: String, record: Record, stillCurrent: () -> Boolean = { true }): Boolean {
        initialize()
        if (record.bytes.isEmpty() || record.bytes.size > MAX_PAYLOAD_BYTES || !stillCurrent()) return false
        val header = Header(key, source, revision, record.crop, record.contentThumbnail, record.bytes.size,
            CRC32().apply { update(record.bytes) }.value)
        val name = fileName(key)
        val target = File(directory, name)
        val temporary = File(directory, "${name.removeSuffix(".thumb")}-${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(temporary).use { stream ->
                DataOutputStream(stream).use { output ->
                    output.writeInt(MAGIC)
                    writeString(output, key)
                    writeString(output, source)
                    writeString(output, revision)
                    output.writeBoolean(record.crop)
                    output.writeBoolean(record.contentThumbnail)
                    output.writeInt(record.bytes.size)
                    output.writeLong(header.checksum)
                    output.write(record.bytes)
                }
            }
            if (!stillCurrent() || temporary.length() > maximumBytes) return false
            // An edited source invalidates all sizes of its previous revision, not other sources.
            keysBySource[source]?.toList()?.forEach { oldKey ->
                if (entries[oldKey]?.header?.revision != revision) remove(oldKey)
            }
            if (Files.isSymbolicLink(target.toPath())) throw IOException("AF-THUMB-SYMLINK")
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val entry = Entry(target, header, target.length())
            register(entry)
            trim()
            return key in entries
        } catch (error: IOException) {
            // Re-enter bounded recovery before another write; failed eviction must not
            // allow subsequent writes to grow a cache that is already over its quota.
            initialized = false
            throw error
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                initialized = false
                throw IOException("AF-THUMB-PARTIAL-CLEANUP")
            }
        }
    }

    @Synchronized
    fun invalidateLocal(paths: Collection<String>) {
        if (!directory.exists() && !initialized) return
        initialize()
        val normalized = ThumbnailSources.localPaths(paths)
        invalidate { ThumbnailSources.affectedLocal(it, normalized) }
    }

    @Synchronized
    fun invalidateDocuments(uris: Collection<String>) {
        if (!directory.exists() && !initialized) return
        initialize()
        val authorities = ThumbnailSources.documentAuthorities(uris)
        invalidate { ThumbnailSources.affectedDocument(it, authorities) }
    }

    /** Checks cached identities only; never walks the user's photo directories. */
    fun pruneMissingLocalSources() {
        val candidates = synchronized(this) {
            if (!directory.exists() && !initialized) return
            initialize()
            entries.values.toList()
        }
        val revisions = hashMapOf<String, String?>()
        val stale = arrayListOf<Entry>()
        candidates.forEach { entry ->
            val source = entry.header.source
            if (source.startsWith("local|")) {
                if (!revisions.containsKey(source)) {
                    val file = File(source.removePrefix("local|"))
                    revisions[source] = if (file.isFile) "${file.lastModified()}:${file.length()}" else null
                }
                if (revisions[source] != entry.header.revision) stale += entry
            }
        }
        synchronized(this) {
            stale.forEach { old ->
                // Do not remove a new revision written while filesystem checks were running.
                if (entries[old.header.key]?.header == old.header) remove(old.header.key)
            }
        }
    }

    @Synchronized
    fun snapshot(): Snapshot {
        initialize()
        return Snapshot(entries.size, bytes, corruptEntries, indexBytes)
    }

    @Synchronized
    fun contains(key: String): Boolean {
        initialize()
        return entries[key]?.file?.isFile == true
    }

    private fun invalidate(predicate: (String) -> Boolean) {
        keysBySource.keys.filter(predicate).flatMap { keysBySource[it]!!.toList() }.forEach(::remove)
    }

    private fun initialize() {
        if (initialized && directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) return
        entries.clear()
        keysBySource.clear()
        bytes = 0L
        indexBytes = 0L
        if (Files.isSymbolicLink(directory.toPath())) throw IOException("AF-THUMB-ROOT")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("AF-THUMB-ROOT")
        val found = ArrayList<Pair<File, Long>>()
        var scanned = 0
        Files.newDirectoryStream(directory.toPath()).use { stream ->
            for (path in stream) {
                if (++scanned > MAX_SCAN_FILES) throw IOException("AF-THUMB-SCAN-LIMIT")
                val file = path.toFile()
                if (partialName.matches(file.name)) {
                    if (!file.delete()) throw IOException("AF-THUMB-PARTIAL-CLEANUP")
                } else if (entryName.matches(file.name)) {
                    found += file to file.lastModified()
                }
            }
        }
        // Sort small file descriptors first, then parse/trim incrementally. Never retain
        // thousands of maximum-sized untrusted headers before enforcing the RAM bound.
        found.sortedBy { it.second }.forEach { (file, _) ->
            val header = try {
                requireRegularFile(file)
                val read = DataInputStream(BufferedInputStream(file.inputStream())).use(::readHeader)
                check(file.name == fileName(read.key)) { "AF-THUMB-KEY" }
                read
            } catch (error: IOException) {
                corruptEntries++
                if (!file.delete()) throw error
                return@forEach
            } catch (error: IllegalStateException) {
                corruptEntries++
                if (!file.delete()) throw error
                return@forEach
            } catch (error: IllegalArgumentException) {
                corruptEntries++
                if (!file.delete()) throw error
                return@forEach
            }
            register(Entry(file, header, file.length()))
            trim()
        }
        initialized = true
    }

    private fun trim() {
        while (entries.size > maximumEntries || bytes > maximumBytes || indexBytes > MAX_INDEX_BYTES) remove(entries.keys.first())
    }

    private fun remove(key: String) {
        val entry = entries[key] ?: return
        if (entry.file.exists() && !entry.file.delete()) throw IOException("AF-THUMB-EVICTION")
        entries.remove(key)
        keysBySource[entry.header.source]?.let { keys ->
            keys.remove(key)
            if (keys.isEmpty()) keysBySource.remove(entry.header.source)
        }
        bytes -= entry.bytes
        indexBytes -= metadataSize(entry.header)
    }

    private fun register(entry: Entry) {
        entries.remove(entry.header.key)?.let { old ->
            bytes -= old.bytes
            indexBytes -= metadataSize(old.header)
            keysBySource[old.header.source]?.let { keys ->
                keys.remove(old.header.key)
                if (keys.isEmpty()) keysBySource.remove(old.header.source)
            }
        }
        entries[entry.header.key] = entry
        keysBySource.getOrPut(entry.header.source) { hashSetOf() }.add(entry.header.key)
        bytes += entry.bytes
        indexBytes += metadataSize(entry.header)
    }

    private fun metadataSize(header: Header): Long =
        2L * (header.key.length + header.source.length + header.revision.length) + 512L

    private fun requireRegularFile(file: File) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            file.length() !in 1..(MAX_PAYLOAD_BYTES + 3L * MAX_STRING_BYTES + 32L)) throw IOException("AF-THUMB-FILE")
    }

    private fun readHeader(input: DataInputStream): Header {
        check(input.readInt() == MAGIC) { "AF-THUMB-VERSION" }
        val key = readString(input)
        val source = readString(input)
        val revision = readString(input)
        val crop = input.readBoolean()
        val content = input.readBoolean()
        val size = input.readInt()
        check(size in 1..MAX_PAYLOAD_BYTES) { "AF-THUMB-LENGTH" }
        val checksum = input.readLong()
        check(checksum in 0..0xffffffffL) { "AF-THUMB-CHECKSUM" }
        return Header(key, source, revision, crop, content, size, checksum)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        check(size in 1..MAX_STRING_BYTES) { "AF-THUMB-METADATA" }
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size in 1..MAX_STRING_BYTES) { "AF-THUMB-METADATA" }
        output.writeInt(encoded.size)
        output.write(encoded)
    }

    private fun fileName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return CharArray(64) { index ->
            val value = digest[index / 2].toInt() and 0xff
            digits[if (index % 2 == 0) value ushr 4 else value and 15]
        }.concatToString() + ".thumb"
    }
}
