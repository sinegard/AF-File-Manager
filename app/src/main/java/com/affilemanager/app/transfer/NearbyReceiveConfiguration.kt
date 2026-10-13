package com.affilemanager.app.transfer

import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.ShareScreenPreferences
import com.affilemanager.app.model.EntryKind
import java.io.File
import org.json.JSONObject

data class NearbyReceiveConfiguration(val concurrentUploads: Int = 1, val typePaths: Map<EntryKind, String> = emptyMap(),
    val approvedRootPaths: List<String> = emptyList()) {
    init {
        require(concurrentUploads in 1..5)
        require(typePaths.size <= 6 && typePaths.keys.all { it in TYPES })
        require(typePaths.values.all { it.length in 1..4096 && it.none(Char::isISOControl) })
        require(approvedRootPaths.size <= 32 && approvedRootPaths.all { it.length in 1..4096 && it.none(Char::isISOControl) })
    }
    fun pathsJson(): String = JSONObject(typePaths.mapKeys { it.key.name }).toString()
    fun approvedDestinations(allowedRoots: List<File>): Map<EntryKind, File> = typePaths.mapValues { (_, path) ->
        val file = File(path).canonicalFile
        require(file.isDirectory && file.canWrite() && allowedRoots.any { FileSystemRules.isContained(it, file) }) { "Gavimo katalogas nepasiekiamas" }
        file
    }
    fun checkMemory() {
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        require(concurrentUploads * 4L * 1024 * 1024 <= available - available / 4) { "Lygiagrečiam gavimui nepakanka atminties" }
    }
    companion object {
        val TYPES = setOf(EntryKind.IMAGE, EntryKind.VIDEO, EntryKind.AUDIO, EntryKind.DOCUMENT, EntryKind.ARCHIVE, EntryKind.APK)
        fun from(preferences: ShareScreenPreferences, approvedRoots: List<String> = emptyList()) = NearbyReceiveConfiguration(preferences.nearbyConcurrentUploads, preferences.nearbyTypePaths, approvedRoots)
        fun parse(concurrent: Int, json: String, roots: List<String> = emptyList()): NearbyReceiveConfiguration {
            require(json.length <= 32 * 1024)
            val parsed = JSONObject(json.ifBlank { "{}" })
            require(parsed.length() <= 6)
            val entries = parsed.keys().asSequence().associate { name -> EntryKind.valueOf(name) to parsed.getString(name) }
            return NearbyReceiveConfiguration(concurrent, entries, roots)
        }
    }
}

internal class NearbyReceiveDestinations(base: File, destinations: Map<EntryKind, File>) {
    private val base = base.canonicalFile
    private val destinations = destinations.mapValues { it.value.canonicalFile.also { file -> require(file.isDirectory && file.canWrite()) } }
    init { require(destinations.size <= 6 && destinations.keys.all { it in NearbyReceiveConfiguration.TYPES }) }
    fun directory(relativeDirectory: String, fileName: String): Pair<File, File> {
        val root = destinations[FileSystemRules.detectKind(fileName, null)] ?: base
        val segments = relativeDirectory.replace('\\', '/').split('/').filter(String::isNotEmpty)
        require(segments.size <= 64 && segments.none { it == "." || it == ".." }) { "Netinkamas gavimo kelias" }
        val target = if (relativeDirectory.isBlank()) root else File(root, relativeDirectory).canonicalFile
        require(FileSystemRules.isContained(root, target)) { "Tikslas išeina už pasirinkto katalogo" }
        require(target.isDirectory || target.mkdirs()) { "Gavimo katalogas nepasiekiamas" }
        require(target.canWrite()) { "Gavimo katalogas neleidžia rašyti" }
        return root to target
    }
}
