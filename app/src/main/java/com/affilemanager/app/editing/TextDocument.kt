package com.affilemanager.app.editing

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class TextEncoding(
    val label: String,
    internal val charsetName: String,
    internal val byteOrderMark: ByteArray = byteArrayOf(),
) {
    UTF8("UTF-8", "UTF-8"),
    UTF8_BOM("UTF-8 BOM", "UTF-8", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    UTF16_LE("UTF-16 LE", "UTF-16LE", byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    UTF16_BE("UTF-16 BE", "UTF-16BE", byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
    WINDOWS_1252("Windows-1252", "windows-1252"),
    ISO_8859_1("ISO-8859-1", "ISO-8859-1"),
    ;

    internal val charset: Charset get() = Charset.forName(charsetName)
}

enum class LineEnding(val label: String, internal val separator: String) {
    LF("LF", "\n"),
    CRLF("CRLF", "\r\n"),
    CR("CR", "\r"),
}

data class TextDocument(
    val text: String,
    val encoding: TextEncoding,
    val lineEnding: LineEnding,
)

object TextDocumentCodec {
    fun decode(bytes: ByteArray, forcedEncoding: TextEncoding? = null): TextDocument {
        val detected = forcedEncoding ?: detectEncoding(bytes)
        val contentOffset = matchingBomLength(bytes, detected)
        val decoded = decodeStrict(bytes, contentOffset, detected.charset)
        val lineEnding = dominantLineEnding(decoded)
        return TextDocument(
            text = normalizeLineEndings(decoded),
            encoding = detected,
            lineEnding = lineEnding,
        )
    }

    fun encode(document: TextDocument): ByteArray {
        require(document.text.length <= EditLimits.MAX_TEXT_CHARS) { "Text exceeds the editor character limit" }
        val normalized = normalizeLineEndings(document.text)
        val serialized = if (document.lineEnding == LineEnding.LF) {
            normalized
        } else {
            normalized.replace("\n", document.lineEnding.separator)
        }
        val body = encodeStrict(serialized, document.encoding.charset)
        val totalSize = Math.addExact(body.size, document.encoding.byteOrderMark.size)
        require(totalSize <= EditLimits.MAX_TEXT_BYTES) { "Encoded text exceeds the editor byte limit" }
        return ByteArray(totalSize).also { combined ->
            document.encoding.byteOrderMark.copyInto(combined)
            body.copyInto(combined, destinationOffset = document.encoding.byteOrderMark.size)
            body.fill(0)
        }
    }

    private fun detectEncoding(bytes: ByteArray): TextEncoding {
        return when {
            bytes.startsWith(TextEncoding.UTF8_BOM.byteOrderMark) -> TextEncoding.UTF8_BOM
            bytes.startsWith(TextEncoding.UTF16_LE.byteOrderMark) -> TextEncoding.UTF16_LE
            bytes.startsWith(TextEncoding.UTF16_BE.byteOrderMark) -> TextEncoding.UTF16_BE
            looksLikeUtf16(bytes, littleEndian = true) -> TextEncoding.UTF16_LE
            looksLikeUtf16(bytes, littleEndian = false) -> TextEncoding.UTF16_BE
            canDecodeStrict(bytes, Charsets.UTF_8) -> TextEncoding.UTF8
            canDecodeStrict(bytes, TextEncoding.WINDOWS_1252.charset) -> TextEncoding.WINDOWS_1252
            else -> TextEncoding.ISO_8859_1
        }
    }

    private fun looksLikeUtf16(bytes: ByteArray, littleEndian: Boolean): Boolean {
        if (bytes.size < 4 || bytes.size % 2 != 0) return false
        val sampleSize = bytes.size.coerceAtMost(4_096)
        var expectedZeros = 0
        var unexpectedZeros = 0
        var index = 0
        while (index + 1 < sampleSize) {
            val firstZero = bytes[index].toInt() == 0
            val secondZero = bytes[index + 1].toInt() == 0
            if (littleEndian) {
                if (secondZero) expectedZeros += 1
                if (firstZero) unexpectedZeros += 1
            } else {
                if (firstZero) expectedZeros += 1
                if (secondZero) unexpectedZeros += 1
            }
            index += 2
        }
        val pairs = sampleSize / 2
        return expectedZeros >= (pairs / 4).coerceAtLeast(1) && unexpectedZeros <= pairs / 20
    }

    private fun dominantLineEnding(text: String): LineEnding {
        var crlf = 0
        var lf = 0
        var cr = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\r' -> {
                    if (index + 1 < text.length && text[index + 1] == '\n') {
                        crlf += 1
                        index += 1
                    } else {
                        cr += 1
                    }
                }
                '\n' -> lf += 1
            }
            index += 1
        }
        return when {
            crlf >= lf && crlf >= cr && crlf > 0 -> LineEnding.CRLF
            cr > lf && cr > 0 -> LineEnding.CR
            else -> LineEnding.LF
        }
    }

    private fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    private fun matchingBomLength(bytes: ByteArray, encoding: TextEncoding): Int =
        encoding.byteOrderMark.takeIf { it.isNotEmpty() && bytes.startsWith(it) }?.size ?: 0

    private fun canDecodeStrict(bytes: ByteArray, charset: Charset): Boolean = try {
        decodeStrict(bytes, 0, charset)
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private fun decodeStrict(bytes: ByteArray, offset: Int, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    }

    private fun encodeStrict(text: String, charset: Charset): ByteArray {
        val encoder = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val encoded = encoder.encode(CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also(encoded::get)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        prefix.size <= size && prefix.indices.all { index -> this[index] == prefix[index] }
}
