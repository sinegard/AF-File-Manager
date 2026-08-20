package com.affilemanager.app.network

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale

/** Minimal RFC 3659 parser that avoids the heavyweight calendar work in the generic FTP parser. */
internal object FtpMachineListParser {
    fun parse(line: String, parentPath: String): RemoteEntry? {
        val separator = line.indexOf(' ')
        require(separator > 0 && separator < line.lastIndex) { "Invalid MLSD entry" }
        val facts = HashMap<String, String>(6)
        line.substring(0, separator).splitToSequence(';').filter(String::isNotEmpty).forEach { fact ->
            val equals = fact.indexOf('=')
            if (equals > 0) {
                facts[fact.substring(0, equals).lowercase(Locale.ROOT)] = fact.substring(equals + 1)
            }
        }
        val type = facts["type"]?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("MLSD type is missing")
        if (type == "cdir" || type == "pdir") return null
        val name = line.substring(separator + 1)
        require(name.isNotEmpty() && name != "." && name != "..") { "Invalid MLSD file name" }
        val directory = type == "dir"
        val size = if (directory) 0L else facts["size"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return RemoteEntry(
            name = name,
            path = RemotePath.join(parentPath, name),
            directory = directory,
            sizeBytes = size,
            modifiedAtMillis = parseModify(facts["modify"]),
        )
    }

    private fun parseModify(value: String?): Long? {
        val timestamp = value?.substringBefore('.') ?: return null
        if (timestamp.length < 14 || timestamp.take(14).any { !it.isDigit() }) return null
        return try {
            LocalDateTime.of(
                timestamp.substring(0, 4).toInt(),
                timestamp.substring(4, 6).toInt(),
                timestamp.substring(6, 8).toInt(),
                timestamp.substring(8, 10).toInt(),
                timestamp.substring(10, 12).toInt(),
                timestamp.substring(12, 14).toInt(),
            ).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }
}
