package com.affilemanager.app.operations

import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.ConflictPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class LocalFileOperator {
    companion object {
        const val MAX_OPERATION_ENTRIES = 200_000
        const val MAX_TREE_DEPTH = 64
        private const val BUFFER_SIZE = 256 * 1_024
    }

    private data class Scan(val items: Int, val bytes: Long)

    suspend fun copyOrMove(
        sourcePaths: List<String>,
        destinationDirectoryPath: String,
        move: Boolean,
        conflictPolicy: ConflictPolicy,
        context: OperationContext,
    ) = withContext(Dispatchers.IO) {
        require(sourcePaths.isNotEmpty()) { "Nepasirinkta failų" }
        val destination = File(destinationDirectoryPath).canonicalFile
        require(destination.isDirectory) { "Paskirties aplankas nepasiekiamas" }

        val sources = sourcePaths.distinct().map { File(it).canonicalFile }
        sources.forEach { source ->
            require(source.exists()) { "Failas nebeegzistuoja: ${source.name}" }
            require(source != destination) { "Negalima kopijuoti aplanko į save" }
            require(!source.isDirectory || !FileSystemRules.isContained(source, destination)) {
                "Negalima kopijuoti aplanko į jo paties poaplankį"
            }
        }

        val totals = sources.fold(Scan(0, 0)) { total, source ->
            val scan = scan(source)
            Scan(Math.addExact(total.items, scan.items), Math.addExact(total.bytes, scan.bytes))
        }
        require(totals.items <= MAX_OPERATION_ENTRIES) { "Operacija viršija $MAX_OPERATION_ENTRIES elementų ribą" }
        context.setTotals(totals.items, totals.bytes)

        sources.forEach { source ->
            context.checkpoint()
            val initialTarget = File(destination, source.name)
            val target = resolveTarget(initialTarget, source.isDirectory, conflictPolicy) ?: return@forEach

            if (move && !target.exists() && source.renameTo(target)) {
                val movedScan = scan(target)
                context.progress(movedScan.items, movedScan.bytes, source.name)
            } else {
                copyRecursively(source, target, conflictPolicy, context, depth = 0)
                if (move) {
                    deleteRecursively(source, context = null, depth = 0)
                }
            }
        }
    }

    suspend fun deletePermanently(paths: List<String>, context: OperationContext) = withContext(Dispatchers.IO) {
        val sources = paths.distinct().map { File(it).canonicalFile }
        val total = sources.fold(Scan(0, 0)) { sum, source ->
            val scan = scan(source)
            Scan(Math.addExact(sum.items, scan.items), Math.addExact(sum.bytes, scan.bytes))
        }
        context.setTotals(total.items, total.bytes)
        sources.forEach { deleteRecursively(it, context, depth = 0) }
    }

    private suspend fun copyRecursively(
        source: File,
        requestedTarget: File,
        conflictPolicy: ConflictPolicy,
        context: OperationContext,
        depth: Int,
    ) {
        require(depth <= MAX_TREE_DEPTH) { "Aplankų gylis viršija $MAX_TREE_DEPTH ribą" }
        context.checkpoint()
        val target = resolveTarget(requestedTarget, source.isDirectory, conflictPolicy) ?: return

        if (source.isDirectory) {
            if (!target.exists()) check(target.mkdir()) { "Nepavyko sukurti ${target.name}" }
            val children = source.listFiles() ?: throw SecurityException("Nepavyko perskaityti ${source.name}")
            children.forEach { child ->
                copyRecursively(child, File(target, child.name), conflictPolicy, context, depth + 1)
            }
            target.setLastModified(source.lastModified())
            context.progress(itemDelta = 1, currentName = source.name)
            return
        }

        val partial = File(target.parentFile, ".${target.name}.${System.nanoTime()}.partial")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        context.checkpoint()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        context.progress(byteDelta = read.toLong(), currentName = source.name)
                    }
                    output.fd.sync()
                }
            }
            require(partial.length() == source.length()) { "Kopijos dydis nesutampa: ${source.name}" }
            if (target.exists() && !target.delete()) throw IllegalStateException("Nepavyko pakeisti ${target.name}")
            if (!partial.renameTo(target)) throw IllegalStateException("Nepavyko užbaigti ${target.name}")
            target.setLastModified(source.lastModified())
            context.progress(itemDelta = 1, currentName = source.name)
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private suspend fun deleteRecursively(file: File, context: OperationContext?, depth: Int) {
        require(depth <= MAX_TREE_DEPTH) { "Aplankų gylis viršija $MAX_TREE_DEPTH ribą" }
        context?.checkpoint()
        if (file.isDirectory) {
            val children = file.listFiles() ?: throw SecurityException("Nepavyko perskaityti ${file.name}")
            children.forEach { deleteRecursively(it, context, depth + 1) }
        }
        val size = if (file.isFile) file.length() else 0
        if (!file.delete()) throw IllegalStateException("Nepavyko ištrinti ${file.name}")
        context?.progress(itemDelta = 1, byteDelta = size, currentName = file.name)
    }

    private fun scan(root: File): Scan {
        var items = 0
        var bytes = 0L
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            val (current, depth) = pending.removeLast()
            require(depth <= MAX_TREE_DEPTH) { "Aplankų gylis viršija $MAX_TREE_DEPTH ribą" }
            items = Math.addExact(items, 1)
            require(items <= MAX_OPERATION_ENTRIES) { "Per daug elementų" }
            if (current.isDirectory) {
                current.listFiles()?.forEach { pending.add(it to depth + 1) }
                    ?: throw SecurityException("Nepavyko perskaityti ${current.name}")
            } else {
                bytes = Math.addExact(bytes, current.length().coerceAtLeast(0))
            }
        }
        return Scan(items, bytes)
    }

    private fun resolveTarget(target: File, sourceIsDirectory: Boolean, policy: ConflictPolicy): File? {
        if (!target.exists()) return target
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> FileSystemRules.keepBothTarget(target)
            ConflictPolicy.REPLACE -> {
                if (target.isDirectory && sourceIsDirectory) target else {
                    require(target.delete()) { "Nepavyko pakeisti ${target.name}" }
                    target
                }
            }
            ConflictPolicy.MERGE -> {
                require(sourceIsDirectory && target.isDirectory) { "Sujungti galima tik aplankus" }
                target
            }
            ConflictPolicy.ASK -> throw FileAlreadyExistsException(target)
        }
    }
}

class FileAlreadyExistsException(val target: File) : IllegalStateException("Jau egzistuoja: ${target.name}")
