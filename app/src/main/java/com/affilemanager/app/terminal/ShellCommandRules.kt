package com.affilemanager.app.terminal

enum class RemoteShellPathStyle {
    POSIX,
    WINDOWS_OPENSSH,
}

object ShellCommandRules {
    private const val MAX_REMOTE_PATH_BYTES = 4_096
    private val windowsDriveName = Regex("^[A-Za-z]:$")
    private val windowsDrivePath = Regex("^/([A-Za-z]:)(?:/(.*))?$")

    fun inferPathStyle(path: String, directoryNames: Collection<String>): RemoteShellPathStyle =
        if (windowsDrivePath.matches(path) || (path == "/" && directoryNames.any(windowsDriveName::matches))) {
            RemoteShellPathStyle.WINDOWS_OPENSSH
        } else {
            RemoteShellPathStyle.POSIX
        }

    fun changeDirectory(
        path: String,
        pathStyle: RemoteShellPathStyle = RemoteShellPathStyle.POSIX,
    ): ByteArray {
        require(path.isNotBlank()) { "Remote terminal path is empty" }
        require(path.none { it == '\u0000' || it == '\r' || it == '\n' }) {
            "The current remote path contains unsupported control characters"
        }
        val commandText = when (pathStyle) {
            RemoteShellPathStyle.POSIX -> {
                val quoted = "'" + path.replace("'", "'\"'\"'") + "'"
                "cd $quoted\r"
            }
            RemoteShellPathStyle.WINDOWS_OPENSSH -> windowsChangeDirectory(path)
        }
        val command = commandText.toByteArray(Charsets.UTF_8)
        require(command.size <= MAX_REMOTE_PATH_BYTES) { "The current remote path is too long for the terminal" }
        return command
    }

    private fun windowsChangeDirectory(path: String): String {
        if (path == "/") return ""
        val match = requireNotNull(windowsDrivePath.matchEntire(path)) {
            "The current Windows SFTP path cannot be mapped to a shell directory"
        }
        val drive = match.groupValues[1]
        val remainder = match.groupValues[2]
        require('"' !in remainder) { "The current Windows path contains an unsupported quote" }
        val windowsPath = if (remainder.isEmpty()) {
            "$drive\\"
        } else {
            "$drive\\${remainder.replace('/', '\\')}"
        }
        return "cd /d \"$windowsPath\"\r"
    }
}
