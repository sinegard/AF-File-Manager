package com.affilemanager.app.data

import java.net.URI
import java.net.URLEncoder

/** A provider grant names one selected tree; it never widens raw filesystem access. */
internal object RemovableStorageAccess {
    private const val AUTHORITY = "com.android.externalstorage.documents"
    private val volumeId = Regex("[A-Za-z0-9-]{1,128}")

    fun needsDocuments(sdk: Int, primary: Boolean, removable: Boolean, mountedWritable: Boolean, rawWritable: Boolean): Boolean =
        sdk in 26..29 && !primary && removable && mountedWritable && !rawWritable

    fun initialTree(id: String): String? = id.takeIf(::validVolume)?.let {
        "content://$AUTHORITY/tree/" + URLEncoder.encode("$it:", "UTF-8")
    }

    fun belongsToVolume(tree: String, id: String, rootOnly: Boolean): Boolean {
        if (!validVolume(id) || tree.length > 8192) return false
        val uri = runCatching { URI(tree) }.getOrNull() ?: return false
        if (uri.scheme != "content" || uri.rawAuthority != AUTHORITY || uri.rawQuery != null || uri.rawFragment != null) return false
        val parts = uri.rawPath.orEmpty().split('/')
        if (parts.size != 3 || parts[0].isNotEmpty() || parts[1] != "tree") return false
        val document = uri.path.orEmpty().removePrefix("/tree/")
        if (!document.substringBefore(':').equals(id, ignoreCase = true) || ':' !in document) return false
        val path = document.substringAfter(':')
        if (rootOnly) return path.isEmpty()
        return path.none { it.isISOControl() || it == '\\' } && path.split('/').none { it == "." || it == ".." }
    }

    private fun validVolume(id: String): Boolean = volumeId.matches(id) && !id.equals("primary", ignoreCase = true)
}
