package com.affilemanager.app.media.editing

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.affilemanager.app.editing.EditLimits
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class NativeMediaFailure(val code: String, val phase: String, val nativeExit: Int? = null, internal val diagnostic: String = "") :
    IllegalStateException("$code ($phase)")

/** No shell, downloaded executable, reflection, native network, or unbounded process output. */
internal class NativeMediaTools(private val context: Context, private val workspace: MediaWorkspace) {
    companion object {
        const val MAX_RSS_KIB = 192L * 1024
        private val processAdmission = Semaphore(1)
    }
    suspend fun ffmpeg(arguments: List<String>, phase: String, progress: (Long) -> Unit = {},
        input: (suspend (java.io.OutputStream) -> Unit)? = null) = run(
        "libffmpeg-cli.so", listOf("-hide_banner", "-nostdin", "-nostats", "-loglevel", "error", "-y",
            "-progress", "pipe:1", "-stats_period", "1", "-threads", "2", "-filter_threads", "1",
            "-filter_complex_threads", "1", "-protocol_whitelist", "file,pipe") + arguments,
        phase, false, progress, input)
    suspend fun probe(arguments: List<String>): String = run("libffprobe-cli.so",
        listOf("-v", "error", "-protocol_whitelist", "file,pipe") + arguments, "probe", true, progress = {}).toString(Charsets.UTF_8)

    private suspend fun run(toolName: String, arguments: List<String>, phase: String, collect: Boolean,
        progress: (Long) -> Unit, input: (suspend (java.io.OutputStream) -> Unit)? = null): ByteArray = processAdmission.withPermit { withContext(Dispatchers.IO) {
        require(arguments.size <= 256 && arguments.all { '\u0000' !in it && it.toByteArray(Charsets.UTF_8).size <= 65536 } &&
            arguments.sumOf { it.toByteArray(Charsets.UTF_8).size } <= 256 * 1024)
        val directory = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        val launcher = File(directory, "libafmediaexec.so")
        val tool = File(directory, toolName)
        if (!launcher.isFile || !launcher.canExecute() || !tool.isFile || !tool.canExecute()) throw NativeMediaFailure("AF_MEDIA_COMPONENT_MISSING", phase)
        val pidFile = workspace.file("pid-${UUID.randomUUID()}")
        val builder = ProcessBuilder(listOf(launcher.path, tool.path) + arguments).directory(workspace.directory)
        builder.environment().apply {
            remove("LD_PRELOAD"); put("LD_LIBRARY_PATH", directory.path); put("AF_MEDIA_PID_FILE", pidFile.path)
            put("LC_ALL", "C"); put("AV_LOG_FORCE_NOCOLOR", "1")
        }
        val process = builder.start()
        val started = SystemClock.elapsedRealtime()
        val nativeContext = currentCoroutineContext()
        val latestProgress = AtomicLong(-1)
        try {
            if (input == null) process.outputStream.close()
            coroutineScope {
                val producer = input?.let { provide -> async(Dispatchers.IO) { process.outputStream.use { provide(it) } } }
                val stdout = async(Dispatchers.IO) {
                    val saved = ByteArrayOutputStream()
                    process.inputStream.use { stream ->
                        val buffer = ByteArray(4096)
                        var total = 0
                        val pending = StringBuilder()
                        while (true) {
                            val size = stream.read(buffer)
                            if (size < 0) break
                            total += size
                            if (total > if (collect) 256 * 1024 else 2 * 1024 * 1024) throw NativeMediaFailure("AF_MEDIA_OUTPUT_LIMIT", phase)
                            if (collect) saved.write(buffer, 0, size)
                            else {
                                pending.append(String(buffer, 0, size, Charsets.US_ASCII))
                                while (true) {
                                    val end = pending.indexOf("\n")
                                    if (end < 0) break
                                    val line = pending.substring(0, end); pending.delete(0, end + 1)
                                    if (line.startsWith("out_time_us=")) line.substringAfter('=').toLongOrNull()?.let(latestProgress::set)
                                }
                                if (pending.length > 4096) throw NativeMediaFailure("AF_MEDIA_OUTPUT_LIMIT", phase)
                            }
                        }
                    }
                    saved.toByteArray()
                }
                // Always drain stderr, but neither retain an unbounded log nor expose private CLI paths.
                val stderr = async(Dispatchers.IO) {
                    val tail = ByteArray(16 * 1024); var position = 0L
                    process.errorStream.use { stream ->
                        val buffer = ByteArray(4096)
                        while (true) { val size = stream.read(buffer); if (size < 0) break
                            for (index in 0 until size) { tail[(position % tail.size).toInt()] = buffer[index]; position++ }
                        }
                    }
                    val size=minOf(position,tail.size.toLong()).toInt()
                    ByteArray(size) { tail[((position-size+it)%tail.size).toInt()] }.toString(Charsets.UTF_8).let { text ->
                        arguments.filter { it.startsWith('/') }.fold(text) { value,path -> value.replace(path,"[private file]") }
                    }
                }
                try {
                  var pid: Int? = null
                  var previousProgress = -1L
                  while (process.isAlive) {
                    nativeContext.ensureActive()
                    if (SystemClock.elapsedRealtime() - started > 30 * 60_000L) throw NativeMediaFailure(MediaExportEngine.TIMEOUT_CODE, phase)
                    if (pid == null && pidFile.isFile && pidFile.length() in 1..32) pid = pidFile.readText().trim().toIntOrNull()
                    if (pid == null && SystemClock.elapsedRealtime() - started > 2000) throw NativeMediaFailure("AF_MEDIA_MEMORY_UNAVAILABLE", phase)
                    pid?.let { requireMemory(it, phase, process, SystemClock.elapsedRealtime()-started) }
                    workspace.checkBudget()
                    val reported = latestProgress.get()
                    if (!collect && reported != previousProgress && reported >= 0) {
                        previousProgress = reported
                        withContext(Dispatchers.Main.immediate) { progress(reported) }
                    }
                    delay(100)
                  }
                  val code = process.waitFor()
                  val output = stdout.await(); val detail=stderr.await(); producer?.await()
                  if (code != 0) throw NativeMediaFailure("AF_MEDIA_NATIVE_FAILED", phase, code, detail)
                  workspace.checkBudget()
                  output
                } finally {
                    // Reap before coroutineScope waits for blocking pipe readers on cancellation.
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (process.isAlive) { process.destroy(); if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
                        if (!process.waitFor(3000, TimeUnit.MILLISECONDS)) throw NativeMediaFailure("AF_MEDIA_STOP_FAILED", phase)
                        process.inputStream.close(); process.errorStream.close()
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (process.isAlive) { process.destroy(); if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
                if (!process.waitFor(3000, TimeUnit.MILLISECONDS)) throw NativeMediaFailure("AF_MEDIA_STOP_FAILED", phase)
                process.inputStream.close(); process.errorStream.close(); pidFile.delete()
            }
        }
    } }
    private fun requireMemory(pid: Int, phase: String, process: Process, elapsedMs: Long) {
        if (pid <= 1) throw NativeMediaFailure("AF_MEDIA_MEMORY_UNAVAILABLE", phase)
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        if(memory.lowMemory || memory.availMem < memory.totalMem/4)throw NativeMediaFailure("AF_MEDIA_MEMORY_LIMIT",phase)
        val status = File("/proc/$pid/status")
        val snapshot=runCatching {status.useLines {it.take(128).joinToString("\n").take(32768)}}.getOrNull()
        val rss=NativeMemoryObservation.rss(snapshot,process.isAlive,elapsedMs,phase)
        if (rss!=null && rss > MAX_RSS_KIB) throw NativeMediaFailure("AF_MEDIA_MEMORY_LIMIT", phase)
        if (workspace.directory.usableSpace < EditLimits.MIN_FREE_BYTES) throw NativeMediaFailure("AF_MEDIA_DISK_LIMIT", phase)
    }
}

/** /proc loses mm/VmRSS during exec/exit, before Java's process reaper necessarily observes exit. */
internal object NativeMemoryObservation {
    fun rss(status:String?,alive:Boolean,elapsedMs:Long,phase:String):Long? {
        val state=status?.lineSequence()?.firstOrNull {it.startsWith("State:")}?.substringAfter(':')?.trim()?.firstOrNull()
        if(!alive || state=='Z' || state=='X')return null
        val value=status?.lineSequence()?.firstOrNull {it.startsWith("VmRSS:")}?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.takeIf {it>=0}
        if(value!=null)return value
        // Same bounded startup deadline as PID discovery; not an unlimited missing-meter bypass.
        if(elapsedMs>2000)throw NativeMediaFailure("AF_MEDIA_MEMORY_UNAVAILABLE",phase)
        return null
    }
}
