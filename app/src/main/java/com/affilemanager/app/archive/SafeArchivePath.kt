package com.affilemanager.app.archive

import com.affilemanager.app.core.FileSystemRules
import java.io.File

object SafeArchivePath {
    fun resolve(destinationRoot: File, entryName: String, maxDepth: Int = 64): File {
        require('\u0000' !in entryName) { "Archyvo įraše yra NUL ženklas" }
        val normalized = entryName.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank()) { "Tuščias archyvo įrašo vardas" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(normalized)) { "Absoliutus Windows kelias archyve neleidžiamas" }
        val components = normalized.split('/').filter(String::isNotBlank)
        require(components.size <= maxDepth) { "Archyvo aplankų gylis viršija ribą" }
        require(components.none { it == "." || it == ".." }) { "Archyvo kelio perėjimas neleidžiamas" }
        val target = components.fold(destinationRoot) { parent, component -> File(parent, component) }
        require(FileSystemRules.isContained(destinationRoot, target)) { "Archyvo įrašas išeina už paskirties aplanko" }
        return target
    }
}
