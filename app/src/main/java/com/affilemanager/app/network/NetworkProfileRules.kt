package com.affilemanager.app.network

object NetworkProfileRules {
    fun normalize(profile: NetworkProfile): NetworkProfile = profile.copy(
        name = profile.name.trim(),
        host = profile.host.trim().removeIpv6Brackets(),
        username = profile.username.trim(),
        basePath = profile.basePath.trim().ifBlank { "/" },
        domain = profile.domain.trim(),
        smbShare = profile.smbShare.trim(),
        expectedHostKeySha256 = profile.expectedHostKeySha256?.trim()?.ifBlank { null },
    )

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
        val host = value.trim()
        return when {
            host.isBlank() -> "Įrašykite serverio IP adresą arba domeną"
            host.length > 253 -> "Serverio adresas per ilgas"
            host.any(Char::isWhitespace) || '\u0000' in host ->
                "Serverio lauke palikite tik vieną IP adreso arba domeno eilutę"
            "://" in host -> "Serverio lauke nerašykite ftp:// ar kitos URL schemos"
            host.any { it == '/' || it == '\\' || it == '@' || it == '?' || it == '#' } ->
                "Serverio lauke įrašykite tik IP adresą arba domeną"
            host.count { it == ':' } == 1 && host.substringAfterLast(':').all(Char::isDigit) ->
                "Prievadą įrašykite atskirame lauke"
            else -> null
        }
    }

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

    private fun String.removeIpv6Brackets(): String =
        if (length >= 2 && first() == '[' && last() == ']' && ':' in this) substring(1, lastIndex) else this
}
