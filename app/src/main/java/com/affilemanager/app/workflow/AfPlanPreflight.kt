package com.affilemanager.app.workflow

import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.operations.TransferVerification
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class AfEnumeratedEntry(
    val relativePath: String,
    val snapshot: AfNodeSnapshot,
    val depth: Int,
)

interface AfStorageSession {
    suspend fun enumerate(source: AfSourceRef): List<AfEnumeratedEntry>
    suspend fun stat(location: AfLocationRef): AfNodeSnapshot?
    suspend fun listChildren(directory: AfLocationRef): List<AfNodeSnapshot>
    suspend fun availableBytes(directory: AfLocationRef): Long?
    suspend fun stagingAvailableBytes(): Long?
    suspend fun sha256(location: AfLocationRef, operation: OperationContext? = null): String
    suspend fun sourceSha256(source: AfSourceRef, entry: AfEnumeratedEntry): String
    suspend fun materialize(
        source: AfSourceRef,
        entry: AfEnumeratedEntry,
        destination: File,
        operation: OperationContext,
    )
    suspend fun createDirectory(location: AfLocationRef)
    suspend fun install(
        sourceFile: File,
        destination: AfLocationRef,
        replace: Boolean,
        operation: OperationContext,
    )
    suspend fun delete(location: AfLocationRef, recursive: Boolean)
    suspend fun rename(from: AfLocationRef, to: AfLocationRef)
    suspend fun close()
}

class AfPlanPreflight {
    suspend fun preview(
        requestedPlan: AfPlanDefinition,
        storage: AfStorageSession,
        nowMillis: Long = System.currentTimeMillis(),
    ): AfPreflightSummary {
        val plan = requestedPlan.normalized(nowMillis)
        val blockers = linkedSetOf<String>()
        val warnings = linkedSetOf<String>()
        val entries = ArrayList<AfPlannedEntry>()
        val sourceBytes = LongArray(plan.sources.size)

        validateOverlappingSources(plan.sources, blockers)
        validateOverlappingDestinations(plan.destinations, blockers)

        plan.sources.forEachIndexed { sourceIndex, source ->
            currentCoroutineContext().ensureActive()
            validateSourceDestinationRelationship(source, plan.destinations, blockers)
            val walked = try {
                storage.enumerate(source)
            } catch (error: Throwable) {
                blockers += "Source unavailable: ${source.displayName} (${safeReason(error)})"
                emptyList()
            }
            if (walked.isEmpty()) {
                blockers += "Source is empty or unavailable: ${source.displayName}"
                return@forEachIndexed
            }
            walked.forEach { item ->
                if (entries.size % 64 == 0) currentCoroutineContext().ensureActive()
                require(item.depth in 0..AfWorkflowLimits.MAX_TREE_DEPTH) { "Source tree is too deep" }
                validateRelativePath(item.relativePath)
                require(entries.size < AfWorkflowLimits.MAX_PLANNED_ENTRIES) { "AF Plan exceeds the item limit" }
                val baseNode = item.snapshot.copy(location = item.snapshot.location.normalized())
                val normalizedNode = if (
                    !baseNode.directory &&
                    (plan.verification == TransferVerification.SHA256 || source.kind == AfSourceKind.ARCHIVE_ENTRY)
                ) {
                    try {
                        baseNode.copy(sha256 = storage.sourceSha256(source, item))
                    } catch (error: Throwable) {
                        blockers += "Source could not be content-verified: ${baseNode.name} (${safeReason(error)})"
                        baseNode
                    }
                } else {
                    baseNode
                }
                entries += AfPlannedEntry(
                    index = entries.size,
                    sourceRootIndex = sourceIndex,
                    relativePath = item.relativePath,
                    source = normalizedNode,
                    depth = item.depth,
                )
                if (!normalizedNode.directory) {
                    sourceBytes[sourceIndex] = Math.addExact(sourceBytes[sourceIndex], normalizedNode.sizeBytes)
                }
            }
        }

        val largestStagedFile = entries.asSequence()
            .filterNot { it.source.directory }
            .maxOfOrNull { it.source.sizeBytes }
        if (largestStagedFile != null) {
            val stagingAvailable = runCatching { storage.stagingAvailableBytes() }.getOrNull()
            if (stagingAvailable == null) {
                warnings += "Private staging space could not be confirmed"
            } else if (
                stagingAvailable < AfWorkflowLimits.MIN_STAGING_RESERVE_BYTES ||
                largestStagedFile > stagingAvailable - AfWorkflowLimits.MIN_STAGING_RESERVE_BYTES
            ) {
                blockers += "Not enough private staging space for the largest file"
            }
        }

        require(entries.size.toLong() * plan.destinations.size.toLong() <= AfWorkflowLimits.MAX_PLANNED_ENTRIES) {
            "AF Plan has too many source/destination combinations"
        }
        val entriesBySource = entries.groupBy(AfPlannedEntry::sourceRootIndex)
        val estimatedPreviewBytes = entries.fold(4_096L) { total, entry ->
            val characterCount = entry.relativePath.length.toLong() + entry.source.location.path.length + entry.source.name.length
            Math.addExact(total, 480L + characterCount * 4L)
        }
        if (estimatedPreviewBytes > AfWorkflowLimits.MAX_METADATA_BYTES * 3 / 4) {
            blockers += "The plan preview would exceed its safe storage limit"
        }

        val projections = ArrayList<AfDestinationProjection>()
        var projectedWriteBytes = 0L
        var readyCopies = 0
        var conflicts = 0
        var verifiedIdentical = 0
        var possiblyIdentical = 0
        var skipped = 0
        var estimatedReceiptItems = 0L
        var estimatedReceiptBytes = 1_024L
        val destinationRequiredBytes = LongArray(plan.destinations.size)

        plan.destinations.forEachIndexed { destinationIndex, destination ->
            currentCoroutineContext().ensureActive()
            val directory = destination.location.normalized()
            val reservedNames = linkedSetOf<String>()
            val directorySnapshot = try {
                storage.stat(directory)
            } catch (error: Throwable) {
                blockers += "Destination unavailable: ${directory.displayLabel} (${safeReason(error)})"
                null
            }
            if (directorySnapshot != null && !directorySnapshot.directory) {
                blockers += "Destination is not a folder: ${directory.displayLabel}"
                return@forEachIndexed
            }
            if (directorySnapshot == null) {
                blockers += "Destination folder does not exist: ${directory.displayLabel}"
                return@forEachIndexed
            }

            plan.sources.forEachIndexed { sourceIndex, source ->
                val rootEntries = entriesBySource[sourceIndex].orEmpty()
                if (rootEntries.isEmpty()) return@forEachIndexed
                val requestedName = safeLeafName(source.displayName)
                val requestedTarget = child(directory, requestedName)
                val sourceRoot = rootEntries.first { it.relativePath.isEmpty() }.source
                val existing = try {
                    storage.stat(requestedTarget)
                } catch (error: Throwable) {
                    blockers += "Could not inspect ${requestedTarget.displayLabel}: ${safeReason(error)}"
                    null
                }
                var resolution = if (requestedName.lowercase(Locale.ROOT) in reservedNames) {
                    resolvePlannedNameCollision(plan, requestedName, directory, reservedNames, storage)
                } else {
                    resolveRoot(
                        plan = plan,
                        sourceRef = source,
                        source = sourceRoot,
                        requestedName = requestedName,
                        requestedTarget = requestedTarget,
                        existing = existing,
                        reservedNames = reservedNames,
                        storage = storage,
                    )
                }
                if (
                    resolution.disposition == AfPreflightDisposition.READY &&
                    plan.conflictPolicy == ConflictPolicy.MERGE &&
                    sourceRoot.directory && existing?.directory == true
                ) {
                    val mergeProblem = inspectMergedTree(rootEntries, requestedTarget, plan.verification, storage)
                    if (mergeProblem != null) {
                        resolution = RootResolution(
                            requestedName,
                            AfPreflightDisposition.BLOCKED,
                            mergeProblem,
                        )
                    }
                }
                if (resolution.disposition == AfPreflightDisposition.BLOCKED) {
                    blockers += resolution.summary ?: "Unresolved destination conflict"
                }
                if (existing != null && resolution.disposition !in setOf(
                        AfPreflightDisposition.VERIFIED_IDENTICAL,
                        AfPreflightDisposition.POSSIBLY_IDENTICAL,
                    )
                ) conflicts += 1
                when (resolution.disposition) {
                    AfPreflightDisposition.VERIFIED_IDENTICAL -> verifiedIdentical += 1
                    AfPreflightDisposition.POSSIBLY_IDENTICAL -> possiblyIdentical += 1
                    AfPreflightDisposition.SKIP -> skipped += 1
                    AfPreflightDisposition.BLOCKED -> Unit
                    else -> {
                        readyCopies += 1
                        projectedWriteBytes = Math.addExact(projectedWriteBytes, sourceBytes[sourceIndex])
                        destinationRequiredBytes[destinationIndex] = Math.addExact(
                            destinationRequiredBytes[destinationIndex],
                            sourceBytes[sourceIndex],
                        )
                    }
                }
                projections += AfDestinationProjection(
                    destinationIndex = destinationIndex,
                    sourceRootIndex = sourceIndex,
                    requestedRootName = requestedName,
                    resolvedRootName = resolution.name,
                    disposition = resolution.disposition,
                    targetRoot = child(directory, resolution.name),
                    conflictSummary = resolution.summary,
                )
                if (resolution.disposition !in setOf(AfPreflightDisposition.SKIP, AfPreflightDisposition.BLOCKED)) {
                    reservedNames += resolution.name.lowercase(Locale.ROOT)
                }
                val receiptEntries = if (resolution.disposition in setOf(
                        AfPreflightDisposition.VERIFIED_IDENTICAL,
                        AfPreflightDisposition.POSSIBLY_IDENTICAL,
                        AfPreflightDisposition.SKIP,
                        AfPreflightDisposition.BLOCKED,
                    )
                ) 1 else rootEntries.size
                estimatedReceiptItems = Math.addExact(estimatedReceiptItems, receiptEntries.toLong())
                estimatedReceiptBytes = Math.addExact(
                    estimatedReceiptBytes,
                    estimateReceiptBytes(source, directory, resolution.name, rootEntries, receiptEntries == 1),
                )
            }

            val available = runCatching { storage.availableBytes(directory) }.getOrNull()
            if (available == null) {
                warnings += "Free space could not be confirmed for ${directory.displayLabel}"
            } else {
                val reserve = minimumFreeReserve(destinationRequiredBytes[destinationIndex])
                if (destinationRequiredBytes[destinationIndex] > available - reserve) {
                    blockers += "Not enough free space at ${directory.displayLabel}"
                }
            }
        }

        if (plan.deleteSourcesAfterVerifiedCopies) {
            if (plan.sources.any { it.kind == AfSourceKind.ARCHIVE_ENTRY }) {
                blockers += "Archive entries cannot be removed after copying"
            }
            val unsafeRequired = projections.any { projection ->
                plan.destinations[projection.destinationIndex].required &&
                    projection.disposition in setOf(AfPreflightDisposition.SKIP, AfPreflightDisposition.BLOCKED)
            }
            if (unsafeRequired) blockers += "Sources cannot be deleted because a required destination will not receive a verified copy"
            estimatedReceiptItems = Math.addExact(estimatedReceiptItems, entries.size.toLong())
            estimatedReceiptBytes = Math.addExact(
                estimatedReceiptBytes,
                entries.sumOf { entry -> 420L + entry.source.location.path.length * 4L },
            )
        }
        if (estimatedReceiptItems > AfWorkflowLimits.MAX_RECEIPT_ITEMS) {
            blockers += "The plan would create too many reliability receipt entries"
        }
        if (estimatedReceiptBytes > AfWorkflowLimits.MAX_RECEIPT_BYTES * 3 / 4) {
            blockers += "The plan reliability receipt would exceed its safe storage limit"
        }

        return AfPreflightSummary(
            executionId = java.util.UUID.randomUUID().toString(),
            plan = plan,
            createdAtMillis = nowMillis,
            entries = entries,
            projections = projections,
            totalSourceBytes = sourceBytes.fold(0L) { total, value -> Math.addExact(total, value) },
            projectedWriteBytes = projectedWriteBytes,
            readyCopies = readyCopies,
            conflicts = conflicts,
            verifiedIdentical = verifiedIdentical,
            possiblyIdentical = possiblyIdentical,
            skipped = skipped,
            blockers = blockers.take(AfWorkflowLimits.MAX_RECORDED_ERRORS),
            warnings = warnings.take(AfWorkflowLimits.MAX_RECORDED_ERRORS),
        )
    }

    private suspend fun resolveRoot(
        plan: AfPlanDefinition,
        sourceRef: AfSourceRef,
        source: AfNodeSnapshot,
        requestedName: String,
        requestedTarget: AfLocationRef,
        existing: AfNodeSnapshot?,
        reservedNames: Set<String>,
        storage: AfStorageSession,
    ): RootResolution {
        if (existing == null) return RootResolution(requestedName, AfPreflightDisposition.READY)
        if (!source.directory && !existing.directory && source.sizeBytes == existing.sizeBytes) {
            if (plan.verification == TransferVerification.SHA256 && sourceRef.kind == AfSourceKind.FILE_SYSTEM) {
                val sourceSha = source.sha256 ?: runCatching { storage.sha256(source.location) }.getOrNull()
                val targetSha = existing.sha256 ?: runCatching { storage.sha256(existing.location) }.getOrNull()
                if (sourceSha != null && sourceSha == targetSha) {
                    return RootResolution(requestedName, AfPreflightDisposition.VERIFIED_IDENTICAL, "Identical content was verified")
                }
            } else {
                return RootResolution(
                    requestedName,
                    AfPreflightDisposition.VERIFIED_IDENTICAL,
                    "Identical size was verified using the selected size check",
                )
            }
        }
        return when (plan.conflictPolicy) {
            ConflictPolicy.ASK -> RootResolution(requestedName, AfPreflightDisposition.BLOCKED, "Choose how to resolve $requestedName")
            ConflictPolicy.SKIP -> RootResolution(requestedName, AfPreflightDisposition.SKIP, "Existing destination will be kept")
            ConflictPolicy.REPLACE -> RootResolution(requestedName, AfPreflightDisposition.REPLACE, "Existing destination will be backed up before replacement")
            ConflictPolicy.MERGE -> if (source.directory && existing.directory) {
                RootResolution(requestedName, AfPreflightDisposition.READY, "Folders will be merged without blind overwrites")
            } else {
                RootResolution(requestedName, AfPreflightDisposition.BLOCKED, "Only folders can be merged: $requestedName")
            }
            ConflictPolicy.KEEP_BOTH -> {
                for (attempt in 1..9_999) {
                    val candidate = keepBothName(requestedName, attempt)
                    if (candidate.lowercase(Locale.ROOT) !in reservedNames &&
                        storage.stat(child(parent(requestedTarget), candidate)) == null
                    ) {
                        return RootResolution(candidate, AfPreflightDisposition.KEEP_BOTH, "Both versions will be kept")
                    }
                }
                RootResolution(requestedName, AfPreflightDisposition.BLOCKED, "A free name could not be found for $requestedName")
            }
        }
    }

    private suspend fun resolvePlannedNameCollision(
        plan: AfPlanDefinition,
        requestedName: String,
        destination: AfLocationRef,
        reservedNames: Set<String>,
        storage: AfStorageSession,
    ): RootResolution = when (plan.conflictPolicy) {
        ConflictPolicy.KEEP_BOTH -> {
            for (attempt in 1..9_999) {
                val candidate = keepBothName(requestedName, attempt)
                if (candidate.lowercase(Locale.ROOT) !in reservedNames && storage.stat(child(destination, candidate)) == null) {
                    return RootResolution(candidate, AfPreflightDisposition.KEEP_BOTH, "Both planned sources will be kept")
                }
            }
            RootResolution(requestedName, AfPreflightDisposition.BLOCKED, "A free name could not be found for $requestedName")
        }
        ConflictPolicy.SKIP -> RootResolution(
            requestedName,
            AfPreflightDisposition.SKIP,
            "Another source in this plan already uses the same destination name",
        )
        else -> RootResolution(
            requestedName,
            AfPreflightDisposition.BLOCKED,
            "Multiple sources in this plan would write to the same destination: $requestedName",
        )
    }

    private suspend fun inspectMergedTree(
        entries: List<AfPlannedEntry>,
        targetRoot: AfLocationRef,
        verification: TransferVerification,
        storage: AfStorageSession,
    ): String? {
        entries.asSequence().filter { it.relativePath.isNotEmpty() }.forEach { entry ->
            val target = child(targetRoot, entry.relativePath)
            val existing = try {
                storage.stat(target)
            } catch (error: Throwable) {
                return "Could not inspect a merged destination item: ${target.displayLabel} (${safeReason(error)})"
            } ?: return@forEach
            if (entry.source.directory && existing.directory) return@forEach
            if (entry.source.directory != existing.directory) {
                return "A file and folder conflict inside the merged tree: ${target.displayLabel}"
            }
            if (entry.source.sizeBytes != existing.sizeBytes) {
                return "Different file content already exists inside the merged tree: ${target.displayLabel}"
            }
            if (verification == TransferVerification.SHA256) {
                val sourceSha = entry.source.sha256
                    ?: return "A merged source file could not be content-verified: ${entry.source.name}"
                val destinationSha = runCatching { storage.sha256(target) }.getOrNull()
                    ?: return "A merged destination file could not be content-verified: ${target.displayLabel}"
                if (sourceSha != destinationSha) {
                    return "Different file content already exists inside the merged tree: ${target.displayLabel}"
                }
            }
        }
        return null
    }

    private fun validateOverlappingSources(sources: List<AfSourceRef>, blockers: MutableSet<String>) {
        val grouped = sources.filter { it.kind == AfSourceKind.FILE_SYSTEM }.groupBy { source ->
            "${source.location.kind}:${source.location.profileId.orEmpty()}"
        }
        grouped.values.forEach { values ->
            findOverlappingLocations(values.map(AfSourceRef::location)).forEach { location ->
                blockers += "Source selections overlap: ${location.displayLabel}"
            }
        }
        sources.filter { it.kind == AfSourceKind.ARCHIVE_ENTRY }
            .groupBy { it.location.identityKey() }
            .values
            .forEach { values ->
                val entries = values.mapNotNull(AfSourceRef::archiveEntryPath)
                findOverlappingPaths(entries).forEach { path -> blockers += "Archive source selections overlap: $path" }
            }
    }

    private fun validateOverlappingDestinations(destinations: List<AfDestinationRef>, blockers: MutableSet<String>) {
        destinations.groupBy { destination ->
            "${destination.location.kind}:${destination.location.profileId.orEmpty()}"
        }.values.forEach { values ->
            findOverlappingLocations(values.map(AfDestinationRef::location)).forEach { location ->
                blockers += "Destination folders overlap: ${location.displayLabel}"
            }
        }
    }

    private fun findOverlappingLocations(locations: List<AfLocationRef>): List<AfLocationRef> {
        val byPath = locations.associateBy { normalizedComparablePath(it.path) }
        return findOverlappingPaths(byPath.keys).mapNotNull(byPath::get)
    }

    private fun findOverlappingPaths(paths: Collection<String>): List<String> {
        val seen = HashSet<String>()
        val overlaps = ArrayList<String>()
        paths.map(::normalizedComparablePath)
            .distinct()
            .sortedWith(
                compareBy<String> { it.count { character -> character == '/' } }
                    .thenBy(String::length),
            )
            .forEach { path ->
                val pieces = path.trim('/').split('/').filter(String::isNotEmpty)
                var prefix = if (path.startsWith('/')) "/" else ""
                var overlap = prefix == "/" && prefix in seen
                pieces.dropLast(1).forEach { piece ->
                    prefix = if (prefix == "/") "/$piece" else if (prefix.isEmpty()) piece else "$prefix/$piece"
                    if (prefix in seen) overlap = true
                }
                if (overlap) overlaps += path
                seen += path
            }
        return overlaps
    }

    private fun normalizedComparablePath(path: String): String = path.replace('\\', '/').trimEnd('/').ifBlank { "/" }

    private fun estimateReceiptBytes(
        source: AfSourceRef,
        destination: AfLocationRef,
        rootName: String,
        entries: List<AfPlannedEntry>,
        rootOnly: Boolean,
    ): Long {
        val selected = if (rootOnly) entries.take(1) else entries
        return selected.fold(0L) { total, entry ->
            val characterCount = source.location.path.length.toLong() + destination.path.length + rootName.length +
                entry.relativePath.length + source.displayName.length
            Math.addExact(total, 640L + characterCount * 4L)
        }
    }

    private fun validateSourceDestinationRelationship(
        source: AfSourceRef,
        destinations: List<AfDestinationRef>,
        blockers: MutableSet<String>,
    ) {
        if (source.kind != AfSourceKind.FILE_SYSTEM) return
        destinations.forEach { destination ->
            val sourceLocation = source.location.normalized()
            val destinationLocation = destination.location.normalized()
            if (sourceLocation.kind != destinationLocation.kind || sourceLocation.profileId != destinationLocation.profileId) return@forEach
            if (sourceLocation.kind == AfLocationKind.LOCAL) {
                val sourceFile = File(sourceLocation.path)
                val destinationFile = File(destinationLocation.path)
                val sourcePath = sourceFile.canonicalFile.toPath()
                val destinationPath = destinationFile.canonicalFile.toPath()
                val projectedTarget = File(destinationFile, safeLeafName(source.displayName)).canonicalFile.toPath()
                if (destinationPath == sourcePath || destinationPath.startsWith(sourcePath) || projectedTarget == sourcePath) {
                    blockers += "A source cannot be copied into itself: ${source.displayName}"
                }
            } else {
                val sourcePath = sourceLocation.path.trimEnd('/')
                val destinationPath = destinationLocation.path.trimEnd('/')
                val projectedTarget = com.affilemanager.app.network.RemotePath.join(destinationPath, safeLeafName(source.displayName))
                if (destinationPath == sourcePath || destinationPath.startsWith("$sourcePath/") || projectedTarget == sourcePath) {
                    blockers += "A remote source cannot be copied into itself: ${source.displayName}"
                }
            }
        }
    }

    private data class RootResolution(
        val name: String,
        val disposition: AfPreflightDisposition,
        val summary: String? = null,
    )
}

object AfWorkflowPaths {
    fun child(parent: AfLocationRef, relativePath: String): AfLocationRef = joinLocation(parent, relativePath)
    fun parent(location: AfLocationRef): AfLocationRef = parentLocation(location)
}

internal fun child(parent: AfLocationRef, relativePath: String): AfLocationRef = joinLocation(parent, relativePath)

private fun joinLocation(parent: AfLocationRef, relativePath: String): AfLocationRef {
    validateRelativePath(relativePath)
    return when (parent.kind) {
        AfLocationKind.LOCAL -> {
            val root = File(parent.path).canonicalFile
            val target = File(root, relativePath.replace('/', File.separatorChar)).canonicalFile
            require(target.toPath().startsWith(root.toPath()) && target != root) { "Destination escaped its folder" }
            AfLocationRef.local(target.path)
        }
        AfLocationKind.REMOTE -> parent.copy(path = relativePath.split('/').fold(parent.path) { path, part ->
            com.affilemanager.app.network.RemotePath.join(path, part)
        }).normalized()
    }
}

internal fun parent(location: AfLocationRef): AfLocationRef = parentLocation(location)

private fun parentLocation(location: AfLocationRef): AfLocationRef = when (location.kind) {
    AfLocationKind.LOCAL -> AfLocationRef.local(requireNotNull(File(location.path).canonicalFile.parentFile).path)
    AfLocationKind.REMOTE -> location.copy(path = com.affilemanager.app.network.RemotePath.normalize("${location.path}/.."))
}

internal fun validateRelativePath(path: String) {
    require(path.length <= AfWorkflowLimits.MAX_PATH_LENGTH && '\u0000' !in path) { "Invalid relative path" }
    if (path.isEmpty()) return
    val pieces = path.replace('\\', '/').split('/')
    require(pieces.none { it.isBlank() || it == "." || it == ".." }) { "Unsafe relative path" }
}

internal fun safeLeafName(raw: String): String {
    val name = raw.trim().take(255)
    require(name.isNotBlank() && name !in setOf(".", "..")) { "Invalid file name" }
    require(name.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }) { "Invalid file name" }
    return name
}

private fun keepBothName(name: String, attempt: Int): String {
    val dot = name.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < name.lastIndex
    val stem = if (hasExtension) name.substring(0, dot) else name
    val extension = if (hasExtension) name.substring(dot) else ""
    val suffix = " ($attempt)$extension"
    return stem.take((255 - suffix.length).coerceAtLeast(1)) + suffix
}

private fun minimumFreeReserve(bytes: Long): Long = maxOf(32L * 1_024 * 1_024, bytes / 20)

private fun safeReason(error: Throwable): String = error::class.java.simpleName.ifBlank { "unavailable" }

internal fun sha256(file: File, operation: OperationContext? = null): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(256 * 1_024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            operation?.progress(byteDelta = read.toLong(), currentName = file.name)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
