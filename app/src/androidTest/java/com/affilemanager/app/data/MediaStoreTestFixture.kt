package com.affilemanager.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** bulkInsert allocates index rows, not physical files, on newer MediaProviders. */
internal fun materializeIndexedFixture(context: Context, collection: Uri, relativePath: String) {
    val root = File(Environment.getExternalStorageDirectory(), relativePath).canonicalFile
    check(root.parentFile?.name == "Pictures" && root.name.startsWith("af", ignoreCase = true))
    val selection = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        else "${MediaStore.MediaColumns.DATA} LIKE ?"
    val argument = if (Build.VERSION.SDK_INT >= 29) relativePath else "${root.path}/%"
    requireNotNull(context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns.DATA),
        selection, arrayOf(argument), null)).use { cursor ->
        while (cursor.moveToNext()) {
            val file = File(cursor.getString(0)).canonicalFile
            check(file.parentFile == root)
            check(root.isDirectory || root.mkdirs())
            check(file.isFile || file.createNewFile())
        }
    }
}
