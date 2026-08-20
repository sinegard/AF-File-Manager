package com.affilemanager.app.workflow

import com.affilemanager.app.operations.OperationContext
import java.io.File
import java.security.MessageDigest

internal class FakeAfStorage : AfStorageSession {
    private data class Node(
        val directory: Boolean,
        var bytes: ByteArray = byteArrayOf(),
        var modifiedAtMillis: Long = 1L,
    )

    private val nodes = linkedMapOf<String, Pair<AfLocationRef, Node>>()
    var availableBytes: Long? = Long.MAX_VALUE
    var stagingBytes: Long? = Long.MAX_VALUE
    var failInstallAt: String? = null
    var corruptInstallAt: String? = null
    var failDeleteAt: String? = null
    val deleted = mutableListOf<String>()
    val installed = mutableListOf<String>()

    fun location(path: String, profile: String = "server"): AfLocationRef =
        AfLocationRef.remote(profile, profile, path)

    fun directory(path: String, profile: String = "server"): AfLocationRef = location(path, profile).also {
        nodes[it.identityKey()] = it to Node(directory = true)
    }

    fun file(path: String, content: String, profile: String = "server"): AfLocationRef =
        file(path, content.toByteArray(), profile)

    fun file(path: String, content: ByteArray, profile: String = "server"): AfLocationRef = location(path, profile).also {
        nodes[it.identityKey()] = it to Node(directory = false, bytes = content.copyOf())
    }

    fun bytes(location: AfLocationRef): ByteArray? = nodes[location.identityKey()]?.second?.bytes?.copyOf()
    fun exists(location: AfLocationRef): Boolean = location.identityKey() in nodes

    override suspend fun enumerate(source: AfSourceRef): List<AfEnumeratedEntry> {
        require(source.kind == AfSourceKind.FILE_SYSTEM) { "Fake archive enumeration is unsupported" }
        val root = source.location.normalized()
        val rootNode = nodes[root.identityKey()]?.second ?: return emptyList()
        val prefix = root.path.trimEnd('/') + "/"
        val selected = nodes.values.filter { (location, _) ->
            location.kind == root.kind && location.profileId == root.profileId &&
                (location.path == root.path || location.path.startsWith(prefix))
        }
        return selected.map { (location, node) ->
            val relative = if (location.path == root.path) "" else location.path.removePrefix(prefix)
            AfEnumeratedEntry(
                relativePath = relative,
                snapshot = snapshot(location, node),
                depth = if (relative.isEmpty()) 0 else relative.count { it == '/' } + 1,
            )
        }.sortedWith(compareBy<AfEnumeratedEntry> { it.depth }.thenBy { it.relativePath })
            .also { require(it.firstOrNull()?.snapshot?.directory == rootNode.directory) }
    }

    override suspend fun stat(location: AfLocationRef): AfNodeSnapshot? =
        nodes[location.normalized().identityKey()]?.let { (stored, node) -> snapshot(stored, node) }

    override suspend fun listChildren(directory: AfLocationRef): List<AfNodeSnapshot> {
        val normalized = directory.normalized()
        require(nodes[normalized.identityKey()]?.second?.directory == true)
        val prefix = normalized.path.trimEnd('/') + "/"
        return nodes.values.mapNotNull { (candidate, node) ->
            if (candidate.kind != normalized.kind || candidate.profileId != normalized.profileId) return@mapNotNull null
            val relative = candidate.path.removePrefix(prefix)
            if (!candidate.path.startsWith(prefix) || '/' in relative) null else snapshot(candidate, node)
        }.sortedBy(AfNodeSnapshot::name)
    }

    override suspend fun availableBytes(directory: AfLocationRef): Long? = availableBytes

    override suspend fun stagingAvailableBytes(): Long? = stagingBytes

    override suspend fun sha256(location: AfLocationRef, operation: OperationContext?): String {
        val node = nodes[location.normalized().identityKey()]?.second ?: error("Missing fake node")
        require(!node.directory)
        return digest(node.bytes)
    }

    override suspend fun sourceSha256(source: AfSourceRef, entry: AfEnumeratedEntry): String =
        sha256(entry.snapshot.location)

    override suspend fun materialize(
        source: AfSourceRef,
        entry: AfEnumeratedEntry,
        destination: File,
        operation: OperationContext,
    ) {
        val node = nodes[entry.snapshot.location.identityKey()]?.second ?: error("Missing fake source")
        require(!node.directory)
        destination.parentFile?.mkdirs()
        destination.writeBytes(node.bytes)
    }

    override suspend fun createDirectory(location: AfLocationRef) {
        val normalized = location.normalized()
        val existing = nodes[normalized.identityKey()]?.second
        require(existing == null || existing.directory)
        nodes.putIfAbsent(normalized.identityKey(), normalized to Node(directory = true))
    }

    override suspend fun install(
        sourceFile: File,
        destination: AfLocationRef,
        replace: Boolean,
        operation: OperationContext,
    ) {
        val normalized = destination.normalized()
        if (normalized.identityKey() == failInstallAt) error("Injected install failure")
        require(replace || normalized.identityKey() !in nodes)
        val source = sourceFile.readBytes()
        val installed = if (normalized.identityKey() == corruptInstallAt && source.isNotEmpty()) {
            source.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        } else source
        nodes[normalized.identityKey()] = normalized to Node(directory = false, bytes = installed)
        this.installed += normalized.identityKey()
    }

    override suspend fun delete(location: AfLocationRef, recursive: Boolean) {
        val normalized = location.normalized()
        val key = normalized.identityKey()
        if (key == failDeleteAt) error("Injected delete failure")
        val node = nodes[key]?.second ?: return
        val prefix = normalized.path.trimEnd('/') + "/"
        val children = nodes.values.filter { (candidate, _) ->
            candidate.kind == normalized.kind && candidate.profileId == normalized.profileId && candidate.path.startsWith(prefix)
        }
        require(recursive || children.isEmpty()) { "Fake folder is not empty" }
        children.forEach { nodes.remove(it.first.identityKey()) }
        nodes.remove(key)
        deleted += key
        require(node.directory || children.isEmpty())
    }

    override suspend fun rename(from: AfLocationRef, to: AfLocationRef) {
        val source = from.normalized()
        val destination = to.normalized()
        require(source.kind == destination.kind && source.profileId == destination.profileId)
        require(destination.identityKey() !in nodes)
        val sourcePair = nodes[source.identityKey()] ?: error("Missing fake rename source")
        val prefix = source.path.trimEnd('/') + "/"
        val moving = nodes.values.filter { (candidate, _) ->
            candidate.kind == source.kind && candidate.profileId == source.profileId &&
                (candidate.path == source.path || candidate.path.startsWith(prefix))
        }
        moving.forEach { nodes.remove(it.first.identityKey()) }
        moving.forEach { (candidate, node) ->
            val suffix = if (candidate.path == source.path) "" else candidate.path.removePrefix(prefix)
            val target = if (suffix.isEmpty()) destination else child(destination, suffix)
            nodes[target.identityKey()] = target to node
        }
        require(sourcePair.second.directory || moving.size == 1)
    }

    override suspend fun close() = Unit

    private fun snapshot(location: AfLocationRef, node: Node): AfNodeSnapshot = AfNodeSnapshot(
        location = location,
        name = location.path.substringAfterLast('/').ifBlank { "/" },
        directory = node.directory,
        sizeBytes = if (node.directory) 0 else node.bytes.size.toLong(),
        modifiedAtMillis = node.modifiedAtMillis,
        sha256 = if (node.directory) null else digest(node.bytes),
    )

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
