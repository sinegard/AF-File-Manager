package com.affilemanager.app.ui.preview

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.Charset
import java.util.Base64

internal data class OfflineWebPart(val mime: String, val charset: String?, val bytes: ByteArray)
internal data class OfflineWebDocument(val html: String, val baseUrl: String, val parts: Map<String, OfflineWebPart>) {
    companion object {
        const val MAX_HTML = 2 * 1024 * 1024
        const val MAX_MHT = 8 * 1024 * 1024
        private const val MAX_PART = 4 * 1024 * 1024
        private const val MAX_PARTS = 128
        private const val MAX_HEADERS = 16 * 1024
        private const val ORIGIN = "https://af-preview.invalid/"

        fun load(input: InputStream, mht: Boolean, checkActive: () -> Unit = {}): OfflineWebDocument {
            val raw = readBounded(input, if (mht) MAX_MHT else MAX_HTML, checkActive)
            val runtime = Runtime.getRuntime()
            val headroom = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            require(raw.size.toLong() * 6 <= headroom - headroom / 4) { "Puslapiui peržiūrėti nepakanka atminties" }
            if (!mht) return OfflineWebDocument(raw.toString(Charsets.UTF_8), ORIGIN, emptyMap())
            return parseMht(raw, checkActive)
        }

        fun parseMht(raw: ByteArray, checkActive: () -> Unit = {}): OfflineWebDocument {
            require(raw.size <= MAX_MHT) { "Puslapio failas per didelis" }
            val source = raw.toString(Charsets.ISO_8859_1)
            val (headers, body) = splitHeaders(source)
            val type = headers["content-type"].orEmpty()
            require(type.substringBefore(';').trim().equals("multipart/related", true)) { "Nepalaikomas MHT formatas" }
            val boundary = parameter(type, "boundary") ?: error("MHT neturi dalies ribos")
            require(boundary.length in 1..200 && boundary.all { it.code in 32..126 }) { "Netinkama MHT dalies riba" }
            val delimiter = Regex("(?m)^--${Regex.escape(boundary)}(--)?[ \t]*(?:\r?\n|$)")
            val marks = delimiter.findAll(body).take(MAX_PARTS + 2).toList()
            require(marks.size in 2..MAX_PARTS + 1 && marks.last().groupValues[1] == "--") { "MHT dalys nepilnos arba jų per daug" }
            var total = 0L
            val parts = linkedMapOf<String, OfflineWebPart>()
            var firstHtml: Pair<String, OfflineWebPart>? = null
            val rootId = parameter(type, "start")?.trim('<', '>')
            var requestedRoot: Pair<String, OfflineWebPart>? = null
            for (index in 0 until marks.lastIndex) {
                checkActive()
                require(marks[index].groupValues[1].isEmpty()) { "Netinkama MHT dalių tvarka" }
                val segmentWithSeparator = body.substring(marks[index].range.last + 1, marks[index + 1].range.first)
                val segment = if (segmentWithSeparator.endsWith("\r\n")) segmentWithSeparator.dropLast(2) else segmentWithSeparator.removeSuffix("\n")
                val (partHeaders, encoded) = splitHeaders(segment)
                val partType = partHeaders["content-type"] ?: "application/octet-stream"
                val mime = partType.substringBefore(';').trim().lowercase()
                require(!mime.startsWith("multipart/")) { "Įdėtinės MHT dalys nepalaikomos" }
                val bytes = when (partHeaders["content-transfer-encoding"].orEmpty().lowercase()) {
                    "base64" -> {
                        val compact = encoded.filterNot(Char::isWhitespace)
                        require(compact.length.toLong() * 3 / 4 <= MAX_PART + 2) { "MHT dalis per didelė" }
                        try { Base64.getDecoder().decode(compact) }
                        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Netinkamas MHT kodavimas") }
                    }
                    "quoted-printable" -> decodeQuotedPrintable(encoded, checkActive)
                    "", "7bit", "8bit", "binary" -> encoded.toByteArray(Charsets.ISO_8859_1)
                    else -> throw IllegalArgumentException("Nepalaikomas MHT kodavimas")
                }
                require(bytes.size <= MAX_PART) { "MHT dalis per didelė" }
                total = Math.addExact(total, bytes.size.toLong())
                require(total <= MAX_MHT) { "MHT išplėtimo riba viršyta" }
                val id = partHeaders["content-id"]?.trim('<', '>')
                val location = partHeaders["content-location"] ?: id?.let { "cid:$it" } ?: "part-$index"
                val url = canonicalUrl(ORIGIN, location) ?: throw IllegalArgumentException("Netinkama MHT dalies vieta")
                val part = OfflineWebPart(mime, parameter(partType, "charset"), bytes)
                require(parts.put(url, part) == null) { "MHT dalies vieta kartojasi" }
                id?.let { require(parts.put("cid:$it", part) == null || "cid:$it" == url) { "MHT dalies vieta kartojasi" } }
                if (mime == "text/html") {
                    if (firstHtml == null) firstHtml = url to part
                    if (id == rootId) requestedRoot = url to part
                }
            }
            val root = (if (rootId != null) requestedRoot else firstHtml) ?: error("MHT neturi HTML puslapio")
            require(root.second.bytes.size <= MAX_HTML) { "HTML puslapis per didelis" }
            val charset = root.second.charset?.let { name ->
                try { Charset.forName(name) } catch (_: IllegalArgumentException) { throw IllegalArgumentException("Nepalaikomas MHT kodavimas") }
            } ?: Charsets.UTF_8
            val base = root.first.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: ORIGIN
            // Relative parts are resolved against the explicit HTML Content-Location, never the filesystem.
            val resolved = linkedMapOf<String, OfflineWebPart>()
            parts.forEach { (url, part) ->
                val key = if (url.startsWith(ORIGIN) && base != ORIGIN) canonicalUrl(base, url.removePrefix(ORIGIN)) ?: url else url
                require(resolved.put(key, part) == null) { "MHT dalies vieta kartojasi" }
            }
            return OfflineWebDocument(root.second.bytes.toString(charset), base, resolved)
        }

        internal fun canonicalUrl(base: String, location: String): String? = runCatching {
            val uri = URI(base).resolve(location).normalize()
            require(uri.scheme?.lowercase() in setOf("https", "http", "cid"))
            require(uri.userInfo == null && location.length <= 4096)
            uri.toString().substringBefore('#')
        }.getOrNull()

        private fun splitHeaders(value: String): Pair<Map<String, String>, String> {
            val crlfSplit = value.indexOf("\r\n\r\n")
            val lfSplit = value.indexOf("\n\n")
            val split = listOf(crlfSplit, lfSplit).filter { it >= 0 }.minOrNull() ?: -1
            require(split in 0..MAX_HEADERS) { "MHT antraštė nepilna arba per didelė" }
            val unfolded = value.substring(0, split).replace("\r\n", "\n").replace(Regex("\n[ \t]+"), " ")
            val headers = linkedMapOf<String, String>()
            unfolded.lineSequence().forEach { line ->
                val colon = line.indexOf(':')
                require(colon > 0) { "Netinkama MHT antraštė" }
                val name = line.substring(0, colon).trim().lowercase()
                require(headers.put(name, line.substring(colon + 1).trim()) == null) { "MHT antraštė kartojasi" }
            }
            return headers to value.substring(split + if (split == crlfSplit) 4 else 2)
        }

        private fun parameter(header: String, name: String): String? =
            Regex("(?:^|;)\\s*${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))", RegexOption.IGNORE_CASE)
                .find(header)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }

        private fun decodeQuotedPrintable(value: String, checkActive: () -> Unit): ByteArray {
            val output = ByteArrayOutputStream(minOf(value.length, MAX_PART))
            var index = 0
            while (index < value.length) {
                if (index % 4096 == 0) checkActive()
                val char = value[index++]
                if (char == '=') {
                    require(index < value.length) { "Netinkamas MHT kodavimas" }
                    if (value[index] == '\n') { index++; continue }
                    if (value[index] == '\r' && value.getOrNull(index + 1) == '\n') { index += 2; continue }
                    require(index + 1 < value.length) { "Netinkamas MHT kodavimas" }
                    output.write(value.substring(index, index + 2).toInt(16)); index += 2
                } else output.write(char.code)
                require(output.size() <= MAX_PART) { "MHT dalis per didelė" }
            }
            return output.toByteArray()
        }

        internal fun readBounded(input: InputStream, maximum: Int, checkActive: () -> Unit = {}): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                require(output.size().toLong() + count <= maximum) { "Puslapio failas per didelis" }
                val runtime = Runtime.getRuntime()
                val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                require((output.size().toLong() + count) * 6 <= available - available / 4) { "Puslapiui peržiūrėti nepakanka atminties" }
                output.write(buffer, 0, count)
            }
        }
    }
}
