package com.affilemanager.app.terminal

/** A bounded fallback for shells that do not publish OSC 133 command markers. */
internal class TerminalOutputCapture(
    private val maximumCaptureBytes: Int = TerminalLimits.MAX_CLIPBOARD_COPY_BYTES * 4,
) {
    private val lock = Any()
    private val output = ByteArray(maximumCaptureBytes.coerceAtLeast(1))
    private val command = ByteArray(MAX_COMMAND_BYTES)
    private var outputSize = 0
    private var commandSize = 0
    private var capturing = false
    private var truncated = false
    private var previousInputWasCarriageReturn = false
    private var submittedCommand = ""

    fun recordAcceptedInput(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset)
        synchronized(lock) {
            repeat(length) { relativeIndex ->
                when (val value = bytes[offset + relativeIndex].toInt() and 0xff) {
                    13 -> {
                        submitCommandLocked()
                        previousInputWasCarriageReturn = true
                    }
                    10 -> {
                        if (!previousInputWasCarriageReturn) submitCommandLocked()
                        previousInputWasCarriageReturn = false
                    }
                    8, 127 -> {
                        if (commandSize > 0) commandSize -= 1
                        previousInputWasCarriageReturn = false
                    }
                    else -> {
                        previousInputWasCarriageReturn = false
                        if (value >= 32 && commandSize < command.size) {
                            command[commandSize++] = value.toByte()
                        }
                    }
                }
            }
        }
    }

    fun recordOutput(bytes: ByteArray, offset: Int = 0, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset)
        synchronized(lock) {
            if (!capturing || length == 0) return
            val accepted = length.coerceAtMost(output.size - outputSize)
            if (accepted > 0) {
                bytes.copyInto(output, outputSize, offset, offset + accepted)
                outputSize += accepted
            }
            if (accepted < length) truncated = true
        }
    }

    fun snapshot(): CapturedTerminalOutput? = synchronized(lock) {
        if (!capturing || outputSize == 0) return@synchronized null
        val cleaned = TerminalOutputText.clean(
            raw = output.copyOf(outputSize),
            submittedCommand = submittedCommand,
        ) ?: return@synchronized null
        CapturedTerminalOutput(cleaned, truncated)
    }

    private fun submitCommandLocked() {
        submittedCommand = TerminalOutputText.decodeCommand(command, commandSize)
        command.fill(0, 0, commandSize)
        commandSize = 0
        output.fill(0, 0, outputSize)
        outputSize = 0
        truncated = false
        capturing = true
    }

    companion object {
        private const val MAX_COMMAND_BYTES = 8 * 1024
    }
}

internal data class CapturedTerminalOutput(
    val text: String,
    val truncated: Boolean,
)

internal object TerminalOutputText {
    private val simplePrompt = Regex("^\\s*[$#>❯]\\s*$")
    private val posixPrompt = Regex("^(?:[^\\r\\n]{0,96}@[^\\r\\n]{1,96}[: ])?[^\\r\\n]{0,160}[$#❯]\\s*$")
    private val powershellPrompt = Regex("^(?:PS\\s+)?(?:[A-Za-z]:)?[^\\r\\n]{0,220}>\\s*$", RegexOption.IGNORE_CASE)

    fun decodeCommand(bytes: ByteArray, length: Int): String = cleanControls(
        bytes.copyOf(length.coerceIn(0, bytes.size)).toString(Charsets.UTF_8),
    ).replace('\n', ' ').trim()

    fun clean(raw: ByteArray, submittedCommand: String): String? {
        if (raw.isEmpty()) return null
        val rendered = cleanControls(raw.toString(Charsets.UTF_8))
        val lines = rendered.split('\n').toMutableList()
        while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)

        val command = submittedCommand.trim()
        if (command.isNotEmpty() && lines.isNotEmpty()) {
            val first = lines.first().trim()
            if (first == command || first.endsWith(command)) lines.removeAt(0)
        }
        while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        if (lines.lastOrNull()?.trim()?.let(::looksLikePrompt) == true) lines.removeAt(lines.lastIndex)
        while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)

        return lines.joinToString("\n").trimEnd().takeIf(String::isNotBlank)
    }

    private fun looksLikePrompt(value: String): Boolean =
        simplePrompt.matches(value) ||
            (('@' in value || value.startsWith('/') || value.startsWith('~')) && posixPrompt.matches(value)) ||
            powershellPrompt.matches(value)

    private fun cleanControls(value: String): String {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                character == '\u001b' -> {
                    index = skipEscapeSequence(value, index)
                }
                character == '\b' -> {
                    if (result.isNotEmpty() && result.last() != '\n') result.deleteCharAt(result.lastIndex)
                    index += 1
                }
                character == '\r' -> {
                    if (index + 1 >= value.length || value[index + 1] != '\n') result.append('\n')
                    index += 1
                }
                character == '\n' || character == '\t' || character >= ' ' -> {
                    if (character != '\u007f') result.append(character)
                    index += 1
                }
                else -> index += 1
            }
        }
        return result.toString()
    }

    private fun skipEscapeSequence(value: String, escapeIndex: Int): Int {
        if (escapeIndex + 1 >= value.length) return value.length
        return when (value[escapeIndex + 1]) {
            '[' -> {
                var index = escapeIndex + 2
                while (index < value.length) {
                    val code = value[index].code
                    index += 1
                    if (code in 0x40..0x7e) break
                }
                index
            }
            ']' -> {
                var index = escapeIndex + 2
                while (index < value.length) {
                    if (value[index] == '\u0007') return index + 1
                    if (value[index] == '\u001b' && index + 1 < value.length && value[index + 1] == '\\') return index + 2
                    index += 1
                }
                index
            }
            else -> (escapeIndex + 2).coerceAtMost(value.length)
        }
    }
}
