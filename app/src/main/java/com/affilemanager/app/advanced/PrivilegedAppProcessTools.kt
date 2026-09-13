package com.affilemanager.app.advanced

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object PrivilegedAppProcessTools {
    private const val MAX_OUTPUT_BYTES = 1_048_576
    private const val MAX_PROCESSES = 2_000
    private const val TIMEOUT_SECONDS = 5L
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+$")

    fun listRunningAppMemory(): Array<String> {
        val preferred = execute(listOf("/system/bin/ps", "-A", "-o", "RSS,NAME"))
        val result = if (preferred.exitCode == 0 && !preferred.truncated) preferred
        else execute(listOf("/system/bin/ps", "-A"))
        require(result.exitCode == 0 && !result.truncated) { "Veikiančių programų sąrašas nepasiekiamas" }
        return parsePsOutput(result.output).entries
            .sortedByDescending(Map.Entry<String, Long>::value)
            .take(MAX_PROCESSES)
            .map { (packageName, bytes) -> "$packageName\t$bytes" }
            .toTypedArray()
    }

    fun forceStopPackage(packageName: String): Boolean {
        requireValidPackageName(packageName)
        val result = execute(listOf("/system/bin/am", "force-stop", "--user", "current", packageName))
        return result.exitCode == 0 && !result.truncated
    }

    fun requireValidPackageName(packageName: String): String {
        require(packageName.length in 3..255 && packagePattern.matches(packageName)) { "Netinkamas paketo pavadinimas" }
        return packageName
    }

    internal fun parsePsOutput(output: String): Map<String, Long> {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).take(MAX_PROCESSES + 1).toList()
        if (lines.size < 2) return emptyMap()
        val header = lines.first().split(Regex("\\s+"))
        val rssIndex = header.indexOfFirst { it.equals("RSS", ignoreCase = true) || it.equals("RSS_KB", ignoreCase = true) }
        val nameIndex = header.indexOfFirst {
            it.equals("NAME", ignoreCase = true) || it.equals("CMD", ignoreCase = true) ||
                it.equals("CMDLINE", ignoreCase = true) || it.equals("ARGS", ignoreCase = true)
        }
        if (rssIndex < 0 || nameIndex < 0) return emptyMap()
        val result = linkedMapOf<String, Long>()
        lines.drop(1).forEach { line ->
            val columns = line.split(Regex("\\s+"))
            if (rssIndex >= columns.size || nameIndex >= columns.size) return@forEach
            val rssKiB = columns[rssIndex].toLongOrNull()?.takeIf { it >= 0L } ?: return@forEach
            val processName = columns[nameIndex].substringBefore(':')
            if (!packagePattern.matches(processName)) return@forEach
            val bytes = runCatching { Math.multiplyExact(rssKiB, 1_024L) }.getOrNull() ?: return@forEach
            result[processName] = runCatching { Math.addExact(result[processName] ?: 0L, bytes) }.getOrNull()
                ?: Long.MAX_VALUE
        }
        return result
    }

    private fun execute(arguments: List<String>): CommandResult {
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val processOutput = process.inputStream
        val output = ByteArrayOutputStream()
        val truncated = AtomicBoolean(false)
        val reader = Thread({
            processOutput.use { input ->
                val buffer = ByteArray(16 * 1_024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = MAX_OUTPUT_BYTES - output.size()
                    if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                    if (count > remaining) truncated.set(true)
                }
            }
        }, "af-privileged-command-output").apply { isDaemon = true; start() }
        val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(1L, TimeUnit.SECONDS)
        }
        reader.join(1_000L)
        if (reader.isAlive) {
            truncated.set(true)
            runCatching { processOutput.close() }
            reader.interrupt()
            reader.join(1_000L)
        }
        check(!reader.isAlive) { "Privilegijuotos komandos išvesties skaitytuvas nesustojo" }
        return CommandResult(
            exitCode = if (completed) process.exitValue() else -1,
            output = output.toString(Charsets.UTF_8.name()),
            truncated = truncated.get(),
        )
    }

    private data class CommandResult(val exitCode: Int, val output: String, val truncated: Boolean)
}
