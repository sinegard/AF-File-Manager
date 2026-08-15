package com.affilemanager.app.terminal

object ShellCommandRules {
    private const val MAX_REMOTE_PATH_BYTES = 4_096

    fun changeDirectory(path: String): ByteArray {
        require(path.isNotBlank()) { "Remote terminal path is empty" }
        require(path.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "The current remote path contains unsupported control characters"
        }
        val quoted = "'" + path.replace("'", "'\"'\"'") + "'"
        val command = "cd $quoted\n".toByteArray(Charsets.UTF_8)
        require(command.size <= MAX_REMOTE_PATH_BYTES) { "The current remote path is too long for the terminal" }
        return command
    }
}
