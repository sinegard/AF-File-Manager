package com.affilemanager.app.network

import java.net.URI
import java.util.Locale

object NetworkProfileRules {
    data class WebDavEndpoint(
        val host: String,
        val port: Int,
        val basePath: String,
        val useTls: Boolean,
    )

    fun normalize(profile: NetworkProfile): NetworkProfile {
        val webDavEndpoint = if (profile.protocol == NetworkProtocol.WEBDAV) parseWebDavEndpoint(profile.host) else null
        val requestedBasePath = profile.basePath.trim().ifBlank { "/" }
        return profile.copy(
            name = profile.name.trim(),
            host = webDavEndpoint?.host ?: sanitizeHostInput(profile.host).removeIpv6Brackets(),
            port = webDavEndpoint?.port ?: profile.port,
            username = profile.username.trim(),
            basePath = webDavEndpoint?.basePath?.takeIf { requestedBasePath == "/" } ?: requestedBasePath,
            domain = profile.domain.trim(),
            smbShare = profile.smbShare.trim(),
            expectedHostKeySha256 = profile.expectedHostKeySha256?.trim()?.ifBlank { null },
            webDavUseTls = webDavEndpoint?.useTls ?: profile.webDavUseTls,
        )
    }

    fun validate(profile: NetworkProfile) {
        val error = nameError(profile.name)
            ?: hostError(profile.host)
            ?: usernameError(profile.username)
            ?: basePathError(profile.basePath)
            ?: domainError(profile.domain)
            ?: shareError(profile.smbShare)
            ?: portError(profile.port)
            ?: when {
                profile.protocol == NetworkProtocol.SMB && profile.smbShare.isBlank() -> "Reikalingas SMB bendrinimo vardas"
                profile.protocol == NetworkProtocol.SFTP && !profile.allowFirstUseTrust &&
                    profile.expectedHostKeySha256?.startsWith("SHA256:") != true ->
                    "SFTP reikia SHA-256 serverio rakto atspaudo arba aiškaus pirmojo pasitikėjimo"
                else -> null
            }
        require(error == null) { requireNotNull(error) }
    }

    fun nameError(value: String): String? = when {
        value.isBlank() -> "Įrašykite jungties pavadinimą"
        value.length > 80 -> "Jungties pavadinimas per ilgas"
        value.hasLineBreakOrNul() -> "Jungties pavadinimas turi būti vienoje eilutėje"
        else -> null
    }

    fun hostError(value: String): String? {
        val host = sanitizeHostInput(value)
        return when {
            host.isBlank() -> "Įrašykite serverio IP adresą arba domeną"
            host.length > 253 -> "Serverio adresas per ilgas"
            host.any { it.isHostLineBreak() || it.isWhitespace() } || '\u0000' in host ->
                "Serverio lauke palikite tik vieną IP adreso arba domeno eilutę"
            "://" in host -> "Serverio lauke nerašykite ftp:// ar kitos URL schemos"
            host.any { it == '/' || it == '\\' || it == '@' || it == '?' || it == '#' } ->
                "Serverio lauke įrašykite tik IP adresą arba domeną"
            host.count { it == ':' } == 1 && host.substringAfterLast(':').all(Char::isDigit) ->
                "Prievadą įrašykite atskirame lauke"
            else -> null
        }
    }

    fun webDavServerError(value: String): String? {
        val sanitized = sanitizeHostInput(value)
        if (sanitized.startsWith("http://", ignoreCase = true) || sanitized.startsWith("https://", ignoreCase = true)) {
            return if (parseWebDavEndpoint(sanitized) == null) {
                "Įrašykite tinkamą HTTP arba HTTPS WebDAV adresą"
            } else {
                null
            }
        }
        return hostError(value)
    }

    fun parseWebDavEndpoint(value: String): WebDavEndpoint? = runCatching {
        val raw = sanitizeHostInput(value)
        if (raw.any { it.isHostLineBreak() } || '\u0000' in raw) return@runCatching null
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return@runCatching null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return@runCatching null
        val host = uri.host?.removeIpv6Brackets()?.takeIf(String::isNotBlank) ?: return@runCatching null
        if (hostError(host) != null) return@runCatching null
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        if (portError(port) != null) return@runCatching null
        WebDavEndpoint(
            host = host,
            port = port,
            basePath = RemotePath.normalize(uri.path.orEmpty().ifBlank { "/" }),
            useTls = scheme == "https",
        )
    }.getOrNull()

    /**
     * Server names and numeric IP addresses cannot contain spacing. Phone keyboards and copy/paste
     * can insert regular spaces, non-breaking spaces, or invisible Unicode format marks, so remove
     * those characters while normalizing the field. Line breaks and NUL deliberately remain invalid
     * instead of joining a pasted multi-line credential block into one value.
     */
    fun sanitizeHostInput(value: String): String = value.filterNot { it.isRemovableHostSeparator() }

    fun usernameError(value: String): String? = when {
        value.length > 256 -> "Naudotojo vardas per ilgas"
        value.hasLineBreakOrNul() -> "Naudotojo vardas turi būti vienoje eilutėje"
        else -> null
    }

    fun basePathError(value: String): String? = when {
        value.length > 4_096 -> "Pradinis kelias per ilgas"
        value.hasLineBreakOrNul() -> "Pradinis kelias turi būti vienoje eilutėje"
        else -> null
    }

    fun domainError(value: String): String? = when {
        value.length > 256 -> "Domenas per ilgas"
        value.hasLineBreakOrNul() -> "Domenas turi būti vienoje eilutėje"
        else -> null
    }

    fun shareError(value: String): String? = when {
        value.length > 256 -> "Bendrinimo vardas per ilgas"
        value.hasLineBreakOrNul() -> "Bendrinimo vardas turi būti vienoje eilutėje"
        else -> null
    }

    fun portError(value: Int): String? = if (value in 1..65_535) null else "Netinkamas prievadas"

    private fun String.hasLineBreakOrNul(): Boolean = any { it == '\r' || it == '\n' || it == '\u0000' }

    private fun Char.isRemovableHostSeparator(): Boolean =
        !isHostLineBreak() &&
            (isWhitespace() || Character.isSpaceChar(this) ||
                this == '\u061c' || this == '\u200b' || this == '\u200e' || this == '\u200f' ||
                this == '\u2060' || this in '\u2066'..'\u2069' || this == '\ufeff')

    private fun Char.isHostLineBreak(): Boolean =
        this == '\r' || this == '\n' || this == '\u0085' || this == '\u2028' || this == '\u2029'

    private fun String.removeIpv6Brackets(): String =
        if (length >= 2 && first() == '[' && last() == ']' && ':' in this) substring(1, lastIndex) else this
}
