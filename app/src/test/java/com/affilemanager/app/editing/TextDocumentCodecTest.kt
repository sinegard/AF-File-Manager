package com.affilemanager.app.editing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TextDocumentCodecTest {
    @Test
    fun detectsBomAndPreservesCrlfWhenReencoded() {
        val original = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "first\r\nsecond\r\n".toByteArray(Charsets.UTF_16LE)

        val document = TextDocumentCodec.decode(original)

        assertEquals(TextEncoding.UTF16_LE, document.encoding)
        assertEquals(LineEnding.CRLF, document.lineEnding)
        assertEquals("first\nsecond\n", document.text)
        assertArrayEquals(original, TextDocumentCodec.encode(document))
    }

    @Test
    fun strictEncodingRefusesCharactersThatWouldBeLost() {
        val document = TextDocument("snowman ☃", TextEncoding.WINDOWS_1252, LineEnding.LF)

        runCatching { TextDocumentCodec.encode(document) }
            .onSuccess { throw AssertionError("Unmappable text must not be silently replaced") }
    }

    @Test
    fun utf8BomAndClassicMacLineEndingsRoundTrip() {
        val original = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a\rb\r".toByteArray()

        val document = TextDocumentCodec.decode(original)

        assertEquals(TextEncoding.UTF8_BOM, document.encoding)
        assertEquals(LineEnding.CR, document.lineEnding)
        assertArrayEquals(original, TextDocumentCodec.encode(document))
    }
}
