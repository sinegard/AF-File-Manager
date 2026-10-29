package com.affilemanager.app.editing

import com.affilemanager.app.advanced.PrivilegedFileMetadata
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Windows content/conflict fixture only; never packaged or claimed as Android security evidence. */
internal fun hostEditSessionStore(cache: File) = EditSessionStore(cache,HostLocalEditFiles)

internal object HostLocalEditFiles : LocalEditFiles {
    override fun metadata(file: File): PrivilegedFileMetadata? {
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return null
        val attrs=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        check(attrs.isRegularFile)
        // This Windows provider reports no fileKey. Creation time is only a host
        // fixture correlation token, NOT an assertion of Android inode security.
        val key=attrs.fileKey()?.hashCode()?.toLong() ?: attrs.creationTime().to(java.util.concurrent.TimeUnit.NANOSECONDS)
        return PrivilegedFileMetadata(1,key,1000,1000,0x8180,null)
    }
    override fun openStage(file: File): LocalEditStage {
        check(file.createNewFile())
        val initial=checkNotNull(metadata(file))
        return object : LocalEditStage {
            override val output=file.outputStream()
            override fun prepare(original: PrivilegedFileMetadata?): PrivilegedFileMetadata {
                output.flush(); output.fd.sync(); output.close()
                check(original==null || original.sameSecurity(initial))
                return initial
            }
            override fun requireNamed(expected: PrivilegedFileMetadata) { check(expected==metadata(file)) }
            override fun close() {
                output.close()
                if(initial==metadata(file))check(file.delete())
            }
        }
    }
}
