package com.affilemanager.app.sharing

import android.app.Application
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import com.affilemanager.app.R
import com.affilemanager.app.archive.ArchiveEngine
import com.affilemanager.app.archive.ArchiveFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LocalShareManager(
    private val application: Application,
    private val archives: ArchiveEngine,
) {
    companion object {
        private const val MAX_DIRECT_ITEMS = 1_000
        private const val MAX_STAGE_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    }

    suspend fun share(paths: Collection<String>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val sources = paths.asSequence().distinct().map(::File).toList()
            require(sources.isNotEmpty()) { "Pasirinkite bent vieną elementą" }
            require(sources.size <= MAX_DIRECT_ITEMS) { "Vienu kartu bendrinama per daug elementų" }
            require(sources.all { it.exists() && it.canRead() }) { "Kai kurie pasirinkti elementai nepasiekiami" }

            val shareDirectory = File(application.cacheDir, "shares").apply {
                require(isDirectory || mkdirs()) { "Laikinos bendrinimo vietos sukurti nepavyko" }
            }
            pruneOldStages(shareDirectory)
            val files = if (sources.any(File::isDirectory)) {
                val archive = File(shareDirectory, "AF-share-${UUID.randomUUID()}.zip")
                archives.create(ArchiveFormat.ZIP, archive, sources)
                listOf(archive)
            } else {
                sources
            }
            launchChooser(files)
            sources.size
        }
    }

    suspend fun sharePrepared(files: Collection<File>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val sources = files.asSequence().distinctBy { it.absolutePath }.toList()
            require(sources.isNotEmpty() && sources.size <= MAX_DIRECT_ITEMS) { "Nėra bendrinamų failų" }
            require(sources.all { it.isFile && it.canRead() }) { "Paruoštas failas nepasiekiamas" }
            val shareRoot = File(application.cacheDir, "shares").apply {
                require(isDirectory || mkdirs()) { "Laikinos bendrinimo vietos sukurti nepavyko" }
            }
            pruneOldStages(shareRoot)
            val stage = File(shareRoot, UUID.randomUUID().toString()).apply {
                require(mkdirs()) { "Laikinos bendrinimo vietos sukurti nepavyko" }
            }
            val copied = sources.mapIndexed { index, source ->
                val safeName = source.name.replace(Regex("[^A-Za-z0-9._() -]"), "_").ifBlank { "file-$index" }
                var target = File(stage, safeName)
                if (target.exists()) target = File(stage, "$index-$safeName")
                source.inputStream().buffered().use { input ->
                    target.outputStream().buffered().use(input::copyTo)
                }
                require(target.length() == source.length()) { "Bendrinimo kopijos dydis nesutampa" }
                target
            }
            launchChooser(copied)
            copied.size
        }
    }

    private fun launchChooser(files: List<File>) {
        val uris = ArrayList(files.map { file ->
            FileProvider.getUriForFile(application, "${application.packageName}.files", file)
        })
        val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (files.size == 1) mimeType(files.single()) else "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(files.first().name, uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        application.startActivity(
            Intent.createChooser(send, application.getString(R.string.share_file_chooser_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun mimeType(file: File): String = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun pruneOldStages(directory: File) {
        val cutoff = System.currentTimeMillis() - MAX_STAGE_AGE_MILLIS
        directory.listFiles().orEmpty().filter { it.lastModified() < cutoff }.forEach { stale ->
            if (stale.isDirectory) stale.deleteRecursively() else stale.delete()
        }
    }
}
