package com.affilemanager.app.transfer

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class NearbyPairing(
    val host: String,
    val port: Int,
    val code: String,
    val receiverName: String = "AF File Manager",
) {
    fun encoded(): String = buildString {
        append(SCHEME).append("://receive?")
        append("host=").append(encode(host))
        append("&port=").append(port)
        append("&code=").append(encode(code))
        append("&name=").append(encode(receiverName.take(MAX_NAME_LENGTH)))
    }

    companion object {
        const val SCHEME = "af-file-manager"
        const val MAX_PAYLOAD_LENGTH = 1_024
        const val MAX_NAME_LENGTH = 64

        fun create(host: String, port: Int, code: String, receiverName: String = "AF File Manager"): NearbyPairing =
            NearbyPairing(host.trim(), port, code, receiverName.trim().ifBlank { "AF File Manager" }).validated()

        fun parse(payload: String): NearbyPairing {
            val normalized = payload.trim()
            require(normalized.length in 1..MAX_PAYLOAD_LENGTH) { "Netinkamas susiejimo kodas" }
            val uri = URI(normalized)
            require(uri.scheme == SCHEME && uri.host == "receive" && uri.fragment == null && uri.userInfo == null) {
                "Tai nėra AF File Manager susiejimo kodas"
            }
            val query = parseQuery(uri.rawQuery.orEmpty())
            return create(
                host = query["host"].orEmpty(),
                port = query["port"]?.toIntOrNull() ?: -1,
                code = query["code"].orEmpty(),
                receiverName = query["name"].orEmpty(),
            )
        }

        private fun NearbyPairing.validated(): NearbyPairing {
            require(isPrivateIpv4(host)) { "Gavimo adresas turi būti privatus IPv4 adresas" }
            require(port in LanTransferOptions.MIN_CUSTOM_PORT..LanTransferOptions.MAX_PORT) { "Netinkamas gavimo prievadas" }
            require(code.length in LanTransferOptions.MIN_PASSWORD_LENGTH..LanTransferOptions.MAX_PASSWORD_LENGTH) {
                "Netinkamas vienkartinis kodas"
            }
            require(code.none(Char::isISOControl)) { "Netinkamas vienkartinis kodas" }
            require(receiverName.length <= MAX_NAME_LENGTH && receiverName.none(Char::isISOControl)) { "Netinkamas įrenginio vardas" }
            return this
        }

        internal fun isPrivateIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { part ->
                if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return false
                if (part.length > 1 && part.startsWith('0')) return false
                part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
            }
            return octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }

        private fun parseQuery(raw: String): Map<String, String> {
            require(raw.length <= MAX_PAYLOAD_LENGTH) { "Susiejimo kodas per ilgas" }
            return raw.split('&').take(8).associate { pair ->
                decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
            }
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

        private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
