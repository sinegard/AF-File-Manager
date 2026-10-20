package com.affilemanager.app.core

/** POSIX arguments only, not commands. No newline can be injected by a file name. */
object CopyPathRules {
    const val MAX_BYTES = 64 * 1024

    fun quotedArguments(paths: Collection<String>): String {
        require(paths.isNotEmpty()) { "Nieko nepasirinkta" }
        var bytes = 0
        return buildString {
            paths.forEachIndexed { index, path ->
                require(path.isNotEmpty() && path.none { it == '\u0000' || it == '\r' || it == '\n' }) {
                    "Pavadinime yra neleistinų ženklų"
                }
                // Check before escaping or allocating a potentially huge argument.
                require(path.length <= MAX_BYTES) { "Tekstas per ilgas" }
                val quoted = "'" + path.replace("'", "'\"'\"'") + "'"
                bytes += quoted.toByteArray(Charsets.UTF_8).size + if (index == 0) 0 else 1
                require(bytes <= MAX_BYTES) { "Tekstas per ilgas" }
                if (index != 0) append(' ')
                append(quoted)
            }
        }
    }
}
