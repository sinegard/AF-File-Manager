@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.media.editing

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Only the one already-authorized input URI can be opened, including nested playlist requests. */
internal fun localMediaSource(context: Context, allowed: Uri) = DataSource.Factory {
    object : DataSource {
        private val delegate: DataSource = if (allowed.scheme == "content") ContentDataSource(context) else FileDataSource()
        override fun open(spec: DataSpec): Long {
            if (spec.uri != allowed || allowed.scheme !in setOf("content", "file")) throw IOException("AF_MEDIA_SOURCE_DENIED")
            return delegate.open(spec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)
        override fun getUri() = delegate.uri
        override fun close() = delegate.close()
        override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
    }
}
