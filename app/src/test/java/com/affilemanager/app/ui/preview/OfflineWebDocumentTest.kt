package com.affilemanager.app.ui.preview

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class OfflineWebDocumentTest {
    private fun mht(parts: String, start: String = "") = ("Content-Type: multipart/related; boundary=\"scope\"$start\r\nMIME-Version: 1.0\r\n\r\n" + parts + "--scope--\r\n").toByteArray(Charsets.ISO_8859_1)
    private fun part(location: String, mime: String, encoding: String, body: String, id: String = "") =
        "--scope\r\nContent-Type: $mime\r\nContent-Location: $location\r\nContent-Transfer-Encoding: $encoding\r\n$id\r\n$body\r\n"

    @Test fun plainHtmlIsReadOnlyUtf8WithAnOfflineOrigin() {
        val html = "<h1>Žodis</h1>"
        val document = OfflineWebDocument.load(html.byteInputStream(), false)
        assertEquals(html, document.html)
        assertTrue(document.parts.isEmpty())
        assertEquals("https://af-preview.invalid/", document.baseUrl)
    }
    @Test fun mhtReadsEncodedHtmlAndOnlyDeclaredResources() {
        val image = byteArrayOf(1, 2, 3, 13, 10, 4)
        val html = "<p>Hello ž</p><img src=\"picture.png\">"
        val bytes = mht(part("https://example.org/page.html", "text/html; charset=UTF-8", "base64", Base64.getEncoder().encodeToString(html.toByteArray())) +
            part("https://example.org/picture.png", "image/png", "base64", Base64.getEncoder().encodeToString(image)))
        val document = OfflineWebDocument.parseMht(bytes)
        assertEquals(html, document.html)
        assertArrayEquals(image, document.parts["https://example.org/picture.png"]?.bytes)
        assertNull(document.parts["https://example.org/missing.png"])
    }
    @Test fun quotedPrintableSoftLineBreakAndUnicodeAreDecoded() {
        val document = OfflineWebDocument.parseMht(mht(part("index.html", "text/html; charset=UTF-8", "quoted-printable", "<p>=C5=BD=\r\nodis</p>")))
        assertEquals("<p>Žodis</p>", document.html)
    }
    @Test fun binaryPartKeepsEmbeddedCrLf() {
        val bytes = byteArrayOf(1, 13, 10, 2)
        val document = OfflineWebDocument.parseMht(mht(part("index.html", "text/html", "7bit", "<p>text</p>") +
            part("image.png", "image/png", "binary", bytes.toString(Charsets.ISO_8859_1))))
        assertArrayEquals(bytes, document.parts["https://af-preview.invalid/image.png"]?.bytes)
    }
    @Test fun absentClosingBoundaryDuplicateLocationsNestedPartsAndBadEncodingFail() {
        val page = part("index.html", "text/html", "7bit", "<p>text</p>")
        assertTrue(runCatching { OfflineWebDocument.parseMht(mht(page).copyOf(40)) }.isFailure)
        assertTrue(runCatching { OfflineWebDocument.parseMht(mht(page + page)) }.isFailure)
        assertTrue(runCatching { OfflineWebDocument.parseMht(mht(part("nested", "multipart/related", "7bit", "x"))) }.isFailure)
        assertTrue(runCatching { OfflineWebDocument.parseMht(mht(part("index.html", "text/html", "base64", "%%%"))) }.isFailure)
    }
    @Test fun declaredRootAndContentIdsAreRespected() {
        val bytes = mht(part("other.html", "text/html", "7bit", "other", "Content-ID: <other>\r\n") +
            part("main.html", "text/html", "7bit", "main", "Content-ID: <main>\r\n"), "; start=\"<main>\"")
        assertEquals("main", OfflineWebDocument.parseMht(bytes).html)
    }
    @Test fun sourceAndPartCountLimitsAreReal() {
        assertTrue(runCatching { OfflineWebDocument.load(ByteArray(OfflineWebDocument.MAX_HTML + 1).inputStream(), false) }.isFailure)
        val many = (0..128).joinToString("") { part("part-$it.html", "text/html", "7bit", "body") }
        assertTrue(runCatching { OfflineWebDocument.parseMht(mht(many)) }.isFailure)
    }
    @Test fun fileContentAndJavascriptLocationsCannotGrantFilesystemAccess() {
        assertNull(OfflineWebDocument.canonicalUrl("https://af-preview.invalid/", "file:///private/secret"))
        assertNull(OfflineWebDocument.canonicalUrl("https://af-preview.invalid/", "content://private/secret"))
        assertNull(OfflineWebDocument.canonicalUrl("https://af-preview.invalid/", "javascript:alert(1)"))
    }

    @Test fun malformedEncodingAndCharsetUseKnownLocalizableErrors() {
        val invalidBase64 = runCatching { OfflineWebDocument.parseMht(mht(part("index.html", "text/html", "base64", "%%%"))) }.exceptionOrNull()
        assertEquals("Netinkamas MHT kodavimas", invalidBase64?.message)
        val invalidCharset = runCatching { OfflineWebDocument.parseMht(mht(part("index.html", "text/html; charset=AF-unsupported", "7bit", "text"))) }.exceptionOrNull()
        assertEquals("Nepalaikomas MHT kodavimas", invalidCharset?.message)
    }

    @Test fun cancellationStopsBeforeReadingOrParsingMoreData() {
        var calls = 0
        val cancelled = runCatching { OfflineWebDocument.load("<p>text</p>".byteInputStream(), false) { calls++; throw kotlinx.coroutines.CancellationException() } }.exceptionOrNull()
        assertTrue(cancelled is kotlinx.coroutines.CancellationException)
        assertEquals(1, calls)
        assertTrue(runCatching { OfflineWebDocument.parseMht(mht(part("index.html", "text/html", "7bit", "text"))) { throw kotlinx.coroutines.CancellationException() } }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
