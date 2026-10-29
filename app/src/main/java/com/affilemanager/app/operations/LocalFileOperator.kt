package com.affilemanager.app.operations

import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.ConflictPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class LocalFileOperator(private val onMutation: suspend (List<File>) -> Unit = {}) {
    companion object {
        const val MAX_OPERATION_ENTRIES = 200_000
        const val MAX_TREE_DEPTH = 64
        private const val BUFFER_SIZE = 1 * 1_024 * 1_024
    }

    private data class Scan(val items: Int, val bytes: Long)
    private data class CopiedSource(val source: File, val target: File, val directory: Boolean, val size: Long, val modified: Long)

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

        val sources = sourcePaths.distinct().map { FileSystemRules.nonSymbolicSource(File(it)) }
        sources.forEach { source ->
            require(source.exists()) { "Failas nebeegzistuoja: ${source.name}" }
            require(source != destination) { "Negalima kopijuoti aplanko į save" }
            require(!source.isDirectory || !FileSystemRules.isContained(source, destination)) {
                "Negalima kopijuoti aplanko į jo paties poaplankį"
            }
        }

        val totals = sources.fold(Scan(0, 0)) { total, source ->
            val scan = scan(source, context, rejectLinks = true)
            Scan(Math.addExact(total.items, scan.items), Math.addExact(total.bytes, scan.bytes))
        }
        require(totals.items <= MAX_OPERATION_ENTRIES) { "Operacija viršija $MAX_OPERATION_ENTRIES elementų ribą" }
        context.setTotals(totals.items, totals.bytes)

        val publishedRoots = linkedSetOf<File>()
        publishingStorageChanges(onMutation, { publishedRoots.toList() }, context) {
            sources.forEach { source ->
                context.checkpoint()
                val initialTarget = File(destination, source.name)
                require(initialTarget.canonicalFile != source) { "Šaltinis ir paskirtis yra tas pats failas" }
                val target = resolveTarget(initialTarget, source.isDirectory, conflictPolicy) ?: return@forEach
                val existingTarget = target.exists()

                val changed = mutableListOf<File>()
                try {
                    if (move && !target.exists() && source.renameTo(target)) {
                        changed += source
                        changed += target
                        val movedScan = scan(target, context)
                        context.progress(movedScan.items, movedScan.bytes, source.name)
                    } else {
                        val copiedSources = if (move) mutableListOf<CopiedSource>() else null
                        val fullyCopied = copyRecursively(source, target, conflictPolicy, context, changed, depth = 0, copiedSources = copiedSources)
                        if (move && fullyCopied) {
                            publishedRoots += source
                            deleteCopiedSources(requireNotNull(copiedSources), context, changed)
                        }
                    }
                } finally {
                    if (changed.isNotEmpty()) {
                        // A merge must not rescan unchanged children of an existing directory.
                        if (existingTarget) publishedRoots.addAll(changed) else publishedRoots += target
                        if (source in changed) publishedRoots += source
                    }
                }
            }
        }
    }

    suspend fun deletePermanently(paths: List<String>, context: OperationContext) = withContext(Dispatchers.IO) {
        val sources = paths.distinct().map { path ->
            val selected = File(path).absoluteFile
            // Resolve ancestors but retain the selected final node so deletion unlinks it.
            selected.parentFile?.canonicalFile?.let { File(it, selected.name) } ?: selected
        }
        val total = sources.fold(Scan(0, 0)) { sum, source ->
            val scan = scan(source, context)
            Scan(Math.addExact(sum.items, scan.items), Math.addExact(sum.bytes, scan.bytes))
        }
        context.setTotals(total.items, total.bytes)
        val publishedRoots = linkedSetOf<File>()
        publishingStorageChanges(onMutation, { publishedRoots.toList() }, context) {
            sources.forEach {
                val changed = mutableListOf<File>()
                try {
                    deleteRecursively(it, context, depth = 0, changed = changed)
                } finally {
                    if (changed.isNotEmpty()) publishedRoots += it
                }
            }
        }
    }

    private suspend fun copyRecursively(
        source: File,
        requestedTarget: File,
        conflictPolicy: ConflictPolicy,
        context: OperationContext,
        changed: MutableList<File>,
        depth: Int,
        copiedSources: MutableList<CopiedSource>?,
    ): Boolean {
        require(depth <= MAX_TREE_DEPTH) { "Aplankų gylis viršija $MAX_TREE_DEPTH ribą" }
        context.checkpoint()
        require(!Files.isSymbolicLink(source.toPath())) { "Simbolinė nuoroda nepalaikoma" }
        val target = resolveTarget(requestedTarget, source.isDirectory, conflictPolicy) ?: return false

        if (source.isDirectory) {
            if (!target.exists()) {
                check(target.mkdir()) { "Nepavyko sukurti ${target.name}" }
                changed += target
            }
            val children = source.listFiles() ?: throw SecurityException("Nepavyko perskaityti ${source.name}")
            var fullyCopied = true
            children.forEach { child ->
                if (!copyRecursively(child, File(target, child.name), conflictPolicy, context, changed, depth + 1, copiedSources)) fullyCopied = false
            }
            target.setLastModified(source.lastModified())
            context.progress(itemDelta = 1, currentName = source.name)
            copiedSources?.add(CopiedSource(source, target, true, 0L, 0L))
            return fullyCopied
        }

        val sourceSize = source.length()
        val sourceModified = source.lastModified()
        val partial = File.createTempFile(".af-copy-", ".partial", target.parentFile)
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
            require(partial.length() == sourceSize && source.length() == sourceSize && source.lastModified() == sourceModified) {
                "Kopijos dydis nesutampa: ${source.name}"
            }
            if (target.exists() && conflictPolicy != ConflictPolicy.REPLACE) throw FileAlreadyExistsException(target)
            if (conflictPolicy == ConflictPolicy.REPLACE) {
                try {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } else Files.move(partial.toPath(), target.toPath())
            changed += target
            target.setLastModified(source.lastModified())
            copiedSources?.add(CopiedSource(source, target, false, sourceSize, sourceModified))
            context.progress(itemDelta = 1, currentName = source.name)
        } finally {
            if (partial.exists()) partial.delete()
        }
        return true
    }

    private suspend fun deleteRecursively(file: File, context: OperationContext?, depth: Int, changed: MutableList<File>) {
        require(depth <= MAX_TREE_DEPTH) { "Aplankų gylis viršija $MAX_TREE_DEPTH ribą" }
        context?.checkpoint()
        val link = Files.isSymbolicLink(file.toPath())
        if (!link && file.isDirectory) {
            val children = file.listFiles() ?: throw SecurityException("Nepavyko perskaityti ${file.name}")
            children.forEach { deleteRecursively(it, context, depth + 1, changed) }
        }
        val regularFile = !link && file.isFile
        val size = if (regularFile) file.length() else 0
        if (!file.delete()) throw IllegalStateException("Nepavyko ištrinti ${file.name}")
        changed += file
        context?.progress(itemDelta = 1, byteDelta = size, currentName = file.name)
    }

    private suspend fun scan(root: File, context: OperationContext, rejectLinks: Boolean = false): Scan {
        var items = 0
        var bytes = 0L
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            if (items % 64 == 0) context.checkpoint()
            val (current, depth) = pending.removeLast()
            require(depth <= MAX_TREE_DEPTH) { "Aplankų gylis viršija $MAX_TREE_DEPTH ribą" }
            items = Math.addExact(items, 1)
            require(items <= MAX_OPERATION_ENTRIES) { "Per daug elementų" }
            if (Files.isSymbolicLink(current.toPath())) {
                require(!rejectLinks) { "Simbolinė nuoroda nepalaikoma" }
                continue
            }
            if (current.isDirectory) {
                current.listFiles()?.forEach { pending.add(it to depth + 1) }
                    ?: throw SecurityException("Nepavyko perskaityti ${current.name}")
            } else {
                bytes = Math.addExact(bytes, current.length().coerceAtLeast(0))
            }
        }
        return Scan(items, bytes)
    }

    private suspend fun deleteCopiedSources(copied: List<CopiedSource>, context: OperationContext, changed: MutableList<File>) {
        // Post-order records contain only successfully copied nodes. Never sweep
        // a source tree again: it can now contain files outside the original copy.
        for (item in copied) {
            context.checkpoint()
            val source = item.source
            require(!Files.isSymbolicLink(source.toPath()) && source.canonicalPath == source.absolutePath) {
                "Simbolinės nuorodos nešalinamos perkeliant"
            }
            require(!Files.isSymbolicLink(item.target.toPath())) { "Simbolinės nuorodos negali būti perrašomos" }
            if (item.directory) {
                require(source.isDirectory && item.target.isDirectory) { "Kopijos dydis nesutampa: ${source.name}" }
            } else {
                require(source.isFile && item.target.isFile && source.length() == item.size && source.lastModified() == item.modified &&
                    digest(source, context).contentEquals(digest(item.target, context))) {
                    "Kopijos dydis nesutampa: ${source.name}"
                }
            }
            check(source.delete()) { "Nepavyko ištrinti ${source.name}" }
            changed += source
        }
    }

    private suspend fun digest(file: File, context: OperationContext): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                context.checkpoint()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun resolveTarget(target: File, sourceIsDirectory: Boolean, policy: ConflictPolicy): File? {
        if (!target.exists()) return target
        return when (policy) {
            ConflictPolicy.SKIP -> null
            ConflictPolicy.KEEP_BOTH -> FileSystemRules.keepBothTarget(target)
            ConflictPolicy.REPLACE -> {
                require(target.isDirectory == sourceIsDirectory) { "Nepavyko pakeisti ${target.name}" }
                target // The old file stays intact until the replacement copy is complete.
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
