package com.affilemanager.app.transfer

data class LanTransferOptions(
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val readOnly: Boolean = false,
) {
    fun validated(protocol: LanTransferProtocol): LanTransferOptions {
        val normalizedUsername = username.trim()
        val normalizedPassword = password.takeUnless(String::isBlank).orEmpty()
        require(port == 0 || port in MIN_CUSTOM_PORT..MAX_PORT) {
            "Prievadas turi būti nuo $MIN_CUSTOM_PORT iki $MAX_PORT arba 0 automatiniam parinkimui"
        }
        if (protocol != LanTransferProtocol.WEB && normalizedUsername.isNotEmpty()) {
            require(normalizedUsername.length <= MAX_USERNAME_LENGTH) { "Naudotojo vardas per ilgas" }
            require(normalizedUsername.none { it.isISOControl() || it == ':' || it.isWhitespace() }) {
                "Naudotojo varde negali būti tarpų, dvitaškio ar valdymo ženklų"
            }
        }
        if (normalizedPassword.isNotEmpty()) {
            require(normalizedPassword.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
                "Slaptažodis turi būti nuo $MIN_PASSWORD_LENGTH iki $MAX_PASSWORD_LENGTH ženklų"
            }
            require(normalizedPassword.none(Char::isISOControl)) { "Slaptažodyje negali būti valdymo ženklų" }
        }
        return copy(
            username = if (protocol == LanTransferProtocol.WEB) "" else normalizedUsername,
            password = normalizedPassword,
        )
    }

    companion object {
        const val MIN_CUSTOM_PORT = 1024
        const val MAX_PORT = 65_535
        const val MAX_USERNAME_LENGTH = 64
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_PASSWORD_LENGTH = 128
    }
}

internal fun validateRequestedSecret(secret: String?): String? = secret?.takeIf(String::isNotBlank)?.also {
    require(it.length in LanTransferOptions.MIN_PASSWORD_LENGTH..LanTransferOptions.MAX_PASSWORD_LENGTH) {
        "Netinkamas laikinas slaptažodis"
    }
    require(it.none(Char::isISOControl)) { "Netinkamas laikinas slaptažodis" }
}

internal fun validateRequestedUsername(username: String?, fallback: String): String =
    (username?.trim()?.takeIf(String::isNotEmpty) ?: fallback).also {
        require(it.length <= LanTransferOptions.MAX_USERNAME_LENGTH) { "Naudotojo vardas per ilgas" }
        require(it.none { character -> character.isISOControl() || character == ':' || character.isWhitespace() }) {
            "Netinkamas naudotojo vardas"
        }
    }

internal fun validateRequestedPort(port: Int): Int = port.also {
    require(it == 0 || it in LanTransferOptions.MIN_CUSTOM_PORT..LanTransferOptions.MAX_PORT) {
        "Netinkamas prievadas"
    }
}
