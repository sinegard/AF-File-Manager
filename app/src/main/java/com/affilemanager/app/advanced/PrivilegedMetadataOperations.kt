package com.affilemanager.app.advanced

import android.os.ParcelFileDescriptor
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Descriptor-pinned operations; authority is always that of the current process. */
internal object PrivilegedMetadataOperations {
    private const val LABEL = "security.selinux"
    private const val PRIVATE_FILE = 0x180 // 0600; creation remains subject to the process umask.
    private const val PRIVATE_DIRECTORY = 0x1c0 // 0700
    // Linux O_CLOEXEC on all four supported Android ABIs; SDK exposes its field only from27.
    // The API26 kernel already supports this flag. Check the actual descriptor as well.
    private const val CLOSE_ON_EXEC = 0x80000

    fun read(path: String): Array<String> = binderCall { withEntry(path) { fd -> readOpenEntry(fd).encode() } }

    internal fun readOpenEntry(fd: FileDescriptor): PrivilegedFileMetadata {
        rejectUncopiedSecurityAttributes(fd)
        return metadata(fd).also { PrivilegedFileMetadata.decode(it.encode()) }
    }

    fun openReadOnly(path: String): ParcelFileDescriptor = try {
        val file = checkedPath(path)
        // Reject devices/FIFOs before open, not only after obtaining a descriptor.
        check(OsConstants.S_ISREG(Os.lstat(file.path).st_mode))
        withEntry(file.path) { fd ->
            check(OsConstants.S_ISREG(Os.fstat(fd).st_mode))
            // AIDL's RETURN_VALUE transfers this duplicate and closes the service copy.
            ParcelFileDescriptor.dup(fd)
        }
    } catch (error: Exception) {
        throw IllegalStateException("Failo perskaityti nepavyko", error)
    }

    fun apply(path: String, fields: Array<String>) = binderCall {
        val expected = PrivilegedFileMetadata.decode(fields)
        withEntry(path) { fd -> applyOpenEntry(fd, expected) }
    }

    internal fun applyOpenEntry(fd: FileDescriptor, expected: PrivilegedFileMetadata) {
            rejectUncopiedSecurityAttributes(fd)
            val before = metadata(fd)
            check(before.mode and OsConstants.S_IFMT == expected.mode and OsConstants.S_IFMT)
            if (before.uid != expected.uid || before.gid != expected.gid) {
                setOwner(fd, expected.uid, expected.gid)
            }
            if (before.selinuxHex != expected.selinuxHex) {
                // Never remove a security label as an unsupported-filesystem fallback.
                val hex = checkNotNull(expected.selinuxHex) { "AF-META-NO-LABEL" }
                val bytes = ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
                descriptorPath(fd) { Os.setxattr(it, LABEL, bytes, 0) }
            }
            // chown may clear set-id bits; set the exact original mode AFTER owner and content.
            if (Os.fstat(fd).st_mode and 0xfff != expected.mode and 0xfff) Os.fchmod(fd, expected.mode and 0xfff)
            val after = metadata(fd)
            check(before.sameEntry(after) && expected.sameSecurity(after)) { "AF-META-VERIFY" }
            if (OsConstants.S_ISREG(after.mode)) Os.fsync(fd)
    }

    fun create(path: String, directory: Boolean) = createEntry(path, directory) { }

    fun openPrivateWrite(path: String): FileDescriptor = createEntry(path, false, retainDescriptor = true) { it }

    private fun <T> createEntry(path: String, directory: Boolean, retainDescriptor: Boolean = false, use: (FileDescriptor) -> T): T = binderCall {
        val target = checkedPath(path)
        val parent = checkNotNull(target.parentFile)
        withEntry(parent.path) { parentFd ->
            val parentBefore = metadata(parentFd)
            check(OsConstants.S_ISDIR(parentBefore.mode))
            var created: PrivilegedFileMetadata? = null
            try {
                val fd = if (directory) {
                    Os.mkdir(target.path, PRIVATE_DIRECTORY)
                    Os.open(target.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or CLOSE_ON_EXEC, 0)
                } else {
                    Os.open(target.path, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or
                        OsConstants.O_NOFOLLOW or CLOSE_ON_EXEC, PRIVATE_FILE)
                }
                var transferred = false
                try {
                    requireCloseOnExec(fd)
                    val initial = metadata(fd)
                    check(OsConstants.S_ISDIR(initial.mode) == directory) { "AF-META-TYPE" }
                    created = initial
                    check(parentBefore.sameEntry(metadata(parentFd))) { "AF-META-PARENT-CHANGED" }
                    val platformSharedStorage = isPlatformSharedStorage(target, initial, parentBefore)
                    // Old storage views project an app owner; newer FUSE may retain the
                    // creating root UID and support chown. Preserve a projected UID,
                    // otherwise give the new private entry to its destination owner.
                    val owner = if (platformSharedStorage && initial.uid != android.os.Process.myUid()) initial else parentBefore
                    if (initial.uid != owner.uid || initial.gid != owner.gid) {
                        setOwner(fd, owner.uid, owner.gid)
                    }
                    // Emulated storage exposes mount-managed permissions/labels, not POSIX modes.
                    // On ordinary filesystems Android, not an arbitrary directory type, owns labels.
                    if (!platformSharedStorage) labelNewEntry(target.path, fd)
                    val after = metadata(fd)
                    check(initial.sameEntry(after) && after.uid == owner.uid && after.gid == owner.gid) {
                        "AF-META-OWNER"
                    }
                    val maximum = if (directory) PRIVATE_DIRECTORY else PRIVATE_FILE
                    if (platformSharedStorage) {
                        check(after.mode and 0x002 == 0) { "AF-META-MODE" }
                        check(initial.selinuxHex == after.selinuxHex) { "AF-META-VERIFY" }
                    } else {
                        check(after.mode and 0x1ff and maximum.inv() == 0) { "AF-META-MODE" }
                    }
                    check(initial.mode and 0x1ff == after.mode and 0x1ff) { "AF-META-UMASK" }
                    check(after.sameEntry(PrivilegedFileMetadata.decode(read(target.path)))) { "AF-META-ENTRY-CHANGED" }
                    val result = use(fd)
                    transferred = retainDescriptor
                    result
                } finally { if (!transferred) Os.close(fd) }
            } catch (error: Throwable) {
                // Only our exact, exclusive and still-empty new inode belongs to this cleanup.
                val owned = created
                if (owned != null) runCatching {
                    withEntry(target.path) { fd ->
                        val empty = if (directory) target.list()?.isEmpty() == true else Os.fstat(fd).st_size == 0L
                        if (owned.sameEntry(metadata(fd)) && empty) {
                            check(target.delete()) { "AF-META-CLEANUP" }
                        }
                    }
                }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
    }

    private fun labelNewEntry(path: String, fd: FileDescriptor) {
        val before = metadata(fd)
        // Query Android's installed policy WITHOUT modifying a pathname that could be swapped.
        // Apply the answer only through our pinned descriptor, never by copying a parent label.
        val process = ProcessBuilder("/system/bin/restorecon", "-n", "-v", path)
            .redirectErrorStream(true).start()
        try {
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { "AF-META-POLICY-LOOKUP" }
            val collected = ByteArrayOutputStream()
            val buffer = ByteArray(2_048)
            while (true) {
                val count = process.inputStream.read(buffer)
                if (count < 0) break
                check(collected.size() + count <= 16_384) { "AF-META-POLICY-LIMIT" }
                collected.write(buffer, 0, count)
            }
            val change = parsePolicyLabelChange(collected.toString(Charsets.UTF_8.name()), path)
            check(before.sameEntry(PrivilegedFileMetadata.decode(read(path)))) { "AF-META-ENTRY-CHANGED" }
            change?.let { (oldLabel, newLabel) ->
                val oldHex = (oldLabel + '\u0000').toByteArray().joinToString("") { "%02x".format(it) }
                check(before.selinuxHex == oldHex) { "AF-META-LABEL-CHANGED" }
                val label = (newLabel + '\u0000').toByteArray()
                check(label.size <= PrivilegedFileMetadata.MAX_LABEL_BYTES)
                descriptorPath(fd) { Os.setxattr(it, LABEL, label, 0) }
                check(metadata(fd).selinuxHex == label.joinToString("") { "%02x".format(it) })
            }
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
    }

    private fun isPlatformSharedStorage(file: File, entry: PrivilegedFileMetadata, parent: PrivilegedFileMetadata): Boolean {
        if (entry.device != parent.device || entry.device == Os.stat("/data").st_dev) return false
        // A root UserService has no normal app attribution. Newer Android rejects its
        // per-app volume-list request, even when it only needs to classify a private file.
        val storage = if (android.os.Build.VERSION.SDK_INT >= 30) Environment.getStorageDirectory().canonicalFile
            else Environment.getExternalStorageDirectory().canonicalFile
        if (!file.path.startsWith(storage.path + "/")) return false
        // The kernel mount type is authoritative. Modern FUSE can retain0600/0700
        // and the creating UID, so ownership/mode alone cannot identify a projection.
        File("/proc/self/mountinfo").inputStream().buffered(8_192).use { input ->
            val line = ByteArrayOutputStream()
            var bytes = 0
            while (true) {
                val value = input.read()
                if (value < 0) break
                check(++bytes <= 65_536 && line.size() < 16_384) { "AF-META-MOUNT-LIMIT" }
                if (value == 10) {
                    val mount = platformStorageMount(line.toString(Charsets.UTF_8.name()), file.path)
                    if (mount != null && Os.stat(mount).st_dev == entry.device) return true
                    line.reset()
                } else line.write(value)
            }
            if (line.size() > 0) {
                val mount = platformStorageMount(line.toString(Charsets.UTF_8.name()), file.path)
                if (mount != null && Os.stat(mount).st_dev == entry.device) return true
            }
        }
        return false
    }

    internal fun platformStorageMount(line: String, path: String): String? {
        require(line.length <= 16_384) { "AF-META-MOUNT-LIMIT" }
        val separator = line.indexOf(" - ")
        if (separator < 0 || line.substring(separator + 3).substringBefore(' ') !in setOf("fuse", "sdcardfs")) return null
        val fields = line.substring(0, separator).split(' ', limit = 7)
        if (fields.size < 6) return null
        val mount = fields[4].replace("\\040", " ").replace("\\011", "\t").replace("\\012", "\n").replace("\\134", "\\")
        return mount.takeIf { it.startsWith('/') && it.none(Char::isISOControl) && path.startsWith("$it/") }
    }

    private inline fun <T> binderCall(block: () -> T): T = try { block() }
    catch (error: Exception) {
        if (error is UnsupportedOperationException && error.message == "Failo prieigos teisių išlaikyti nepavyko") {
            android.util.Log.w("AFPrivilegedIo", "AF-META-OWNER-UNAVAILABLE")
            throw error
        }
        // Parcel does not preserve every nested cause. Keep a path/content-free diagnostic.
        val code = error.message?.takeIf { it.matches(Regex("AF-META-[A-Z-]+")) }
            ?: if (error is ErrnoException) "AF-META-ERRNO-${error.errno}" else "AF-META-ERROR"
        val frame = error.stackTrace.firstOrNull { it.className == PrivilegedMetadataOperations::class.java.name }
        android.util.Log.w("AFPrivilegedIo", "$code:${error.javaClass.simpleName}:${frame?.lineNumber ?: -1}")
        throw IllegalStateException("Failo prieigos teisių išlaikyti nepavyko", error)
    }

    private fun setOwner(fd: FileDescriptor, uid: Int, gid: Int) {
        try { Os.fchown(fd, uid, gid) }
        catch (error: ErrnoException) {
            if (error.errno == OsConstants.EPERM && android.os.Process.myUid() == 2_000) {
                // A shell grant does not include CAP_CHOWN. Refuse before publication;
                // never make the replacement world-writable to disguise lost ownership.
                // This public exception type survives Android's Parcel boundary.
                throw UnsupportedOperationException("Failo prieigos teisių išlaikyti nepavyko", error)
            }
            throw error
        }
    }

    internal fun parsePolicyLabelChange(output: String, path: String): Pair<String, String>? {
        require(output.length <= 16_384) { "AF-META-POLICY-LIMIT" }
        var listing = false
        var listedFiles = 0
        var change: Pair<String, String>? = null
        for (line in output.lineSequence().filter(String::isNotBlank)) {
            if (line.trim() == "SELinux: Loaded file_contexts") continue
            if (Regex("^SELinux:\\s+Loaded\\s+file\\s+context\\s+from:$").matches(line.trim())) {
                check(!listing && listedFiles == 0) { "AF-META-POLICY-FORMAT" }
                listing = true
                continue
            }
            if (listing && line.firstOrNull()?.isWhitespace() == true) {
                // Newer Android lists its loaded policy files. These are information only:
                // never open, execute, or use them as a label or as a replacement pathname.
                check(line.matches(Regex("\\s+/[A-Za-z0-9_./-]+_file_contexts")) &&
                    line.trim().length <= 4_096 && ++listedFiles <= 16) { "AF-META-POLICY-FORMAT" }
                continue
            }
            check(!listing || listedFiles > 0) { "AF-META-POLICY-FORMAT" }
            listing = false
            val match = checkNotNull(Regex("^SELinux:\\s+Relabeling (.+) from ([A-Za-z0-9_:,.-]+) to ([A-Za-z0-9_:,.-]+)\\.$").matchEntire(line)) {
                "AF-META-POLICY-FORMAT"
            }
            check(match.groupValues[1] == path && change == null) { "AF-META-POLICY-FORMAT" }
            change = match.groupValues[2] to match.groupValues[3]
        }
        check(!listing || listedFiles > 0) { "AF-META-POLICY-FORMAT" }
        return change
    }

    private fun metadata(fd: FileDescriptor): PrivilegedFileMetadata {
        val stat = Os.fstat(fd)
        check(OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISDIR(stat.st_mode))
        val label = descriptorPath(fd) { path ->
            try { Os.getxattr(path, LABEL) }
            catch (error: ErrnoException) {
                if (error.errno == OsConstants.ENODATA || error.errno == OsConstants.EOPNOTSUPP) null else throw error
            }
        }
        check(label == null || label.size in 1..PrivilegedFileMetadata.MAX_LABEL_BYTES)
        return PrivilegedFileMetadata(stat.st_dev, stat.st_ino, stat.st_uid, stat.st_gid, stat.st_mode,
            label?.joinToString("") { "%02x".format(it) })
    }

    private inline fun <T> descriptorPath(fd: FileDescriptor, block: (String) -> T): T =
        ParcelFileDescriptor.dup(fd).use { block("/proc/self/fd/${it.fd}") }

    private fun checkedPath(path: String): File {
        require(path.length in 1..4_096 && path.none(Char::isISOControl) && path.startsWith('/'))
        val file = File(path)
        require(file.absolutePath == file.canonicalPath) { "AF-META-PATH" }
        return file
    }

    private inline fun <T> withEntry(path: String, block: (FileDescriptor) -> T): T {
        val file = checkedPath(path)
        val fd = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK or CLOSE_ON_EXEC, 0)
        try {
            requireCloseOnExec(fd)
            val opened = Os.fstat(fd)
            val named = Os.lstat(file.path)
            check(opened.st_dev == named.st_dev && opened.st_ino == named.st_ino &&
                (OsConstants.S_ISREG(opened.st_mode) || OsConstants.S_ISDIR(opened.st_mode))) { "AF-META-ENTRY-CHANGED" }
            return block(fd)
        } finally { Os.close(fd) }
    }

    private fun requireCloseOnExec(fd: FileDescriptor) {
        // fcntlInt became PUBLIC only in30. Older kernels still apply the atomic open flag;
        // retain that original descriptor instead of dup (which clears close-on-exec).
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            check(Os.fcntlInt(fd, OsConstants.F_GETFD, 0) and OsConstants.FD_CLOEXEC != 0) { "AF-META-FD" }
        }
    }

    private fun rejectUncopiedSecurityAttributes(fd: FileDescriptor) {
        // These can override the mode bits. Until explicitly supported, do not silently drop
        // an ACL or executable capability during replacement. Fixed lookups, no enumeration.
        descriptorPath(fd) { path ->
            for (name in arrayOf("system.posix_acl_access", "system.posix_acl_default", "security.capability")) {
                val value = try { Os.getxattr(path, name) } catch (error: ErrnoException) {
                    if (error.errno in intArrayOf(OsConstants.ENODATA, OsConstants.EOPNOTSUPP, OsConstants.ENOSYS)) null else throw error
                }
                requireNoExtraSecurityAttribute(value)
            }
        }
    }

    internal fun requireNoExtraSecurityAttribute(value: ByteArray?) {
        require(value == null || value.isEmpty()) { "AF-META-EXTRA-ATTR" }
    }
}
