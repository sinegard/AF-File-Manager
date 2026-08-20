package com.affilemanager.app.benchmark

import android.app.Activity
import android.os.Bundle
import android.util.Base64
import android.widget.TextView
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProtocol
import kotlinx.coroutines.runBlocking
import java.io.File

/** Exists only in the benchmark variant and is protected by a signature permission. */
class BenchmarkSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = PREPARING }
        setContentView(status)
        Thread({
            runCatching { prepare() }
                .onSuccess { runOnUiThread { status.text = READY } }
                .onFailure { error -> runOnUiThread { status.text = "$FAILED:${error::class.java.simpleName}" } }
        }, "af-benchmark-setup").start()
    }

    private fun prepare() {
        val external = requireNotNull(getExternalFilesDir(null)).canonicalFile
        val benchmarkRoot = File(external, "benchmark").canonicalFile
        require(benchmarkRoot.toPath().startsWith(external.toPath()))
        require(benchmarkRoot.isDirectory || benchmarkRoot.mkdirs())

        val large = File(benchmarkRoot, "large")
        seedDirectory(large, LARGE_COUNT, ".txt") { index -> "AF benchmark file $index\n".toByteArray() }

        val thumbnails = File(benchmarkRoot, "thumbnails")
        val png = Base64.decode(PNG_BASE64, Base64.DEFAULT)
        seedDirectory(thumbnails, THUMBNAIL_COUNT, ".png") { png }

        val graph = (application as AFFileManagerApplication).graph
        graph.navigation.setDirectoryDisplaySettings(
            large.canonicalPath,
            DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.LIST, showThumbnails = false),
        )
        graph.navigation.setDirectoryDisplaySettings(
            thumbnails.canonicalPath,
            DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.GRID, gridColumns = 3, showThumbnails = true),
        )
        runBlocking {
            graph.networkProfiles.save(
                NetworkProfile(
                    id = REMOTE_PROFILE_ID,
                    name = "Benchmark FTP",
                    protocol = NetworkProtocol.FTP,
                    host = "127.0.0.1",
                    port = FTP_PORT,
                    username = "benchmark",
                    basePath = "/",
                ),
                newPassword = "benchmark".toCharArray(),
            ).getOrThrow()
        }
    }

    private fun seedDirectory(directory: File, count: Int, extension: String, bytes: (Int) -> ByteArray) {
        val marker = File(directory, ".af-benchmark-$count$extension")
        val ready = marker.isFile && directory.listFiles()?.count { !it.name.startsWith('.') } == count
        if (ready) return
        val benchmarkRoot = requireNotNull(directory.parentFile).canonicalFile
        val target = directory.canonicalFile
        require(target.toPath().startsWith(benchmarkRoot.toPath()) && target != benchmarkRoot)
        if (target.exists()) require(target.deleteRecursively())
        require(target.mkdirs())
        repeat(count) { index ->
            val name = "item-${index.toString().padStart(5, '0')}$extension"
            File(target, name).outputStream().buffered().use { it.write(bytes(index)) }
        }
        marker.writeText("ready")
    }

    companion object {
        const val READY = "BENCHMARK_READY"
        const val PREPARING = "BENCHMARK_PREPARING"
        const val FAILED = "BENCHMARK_FAILED"
        const val REMOTE_PROFILE_ID = "af-benchmark-ftp"
        const val FTP_PORT = 21210
        const val LARGE_COUNT = 10_000
        const val THUMBNAIL_COUNT = 400
        private const val PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
