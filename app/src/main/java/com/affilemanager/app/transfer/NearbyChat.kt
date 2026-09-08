package com.affilemanager.app.transfer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets
import java.util.UUID

data class NearbyChatMessage(
    val id: String,
    val senderName: String,
    val body: String,
    val outgoing: Boolean,
    val timestampMillis: Long,
)

data class NearbyChatState(
    val messages: List<NearbyChatMessage> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
)

/** Process-only chat for the explicitly paired phones. Messages are never persisted or logged. */
object NearbyChatController {
    const val MAX_MESSAGE_CHARACTERS = 1_000
    const val MAX_MESSAGE_BYTES = 4_096
    const val MAX_VISIBLE_MESSAGES = 100

    private val mutableState = MutableStateFlow(NearbyChatState())
    val state: StateFlow<NearbyChatState> = mutableState.asStateFlow()
    private var sessionIdentity: String? = null

    @Synchronized
    fun beginSession(pairing: NearbyPairing) {
        val identity = "${pairing.host}:${pairing.port}:${pairing.receiverName}"
        if (sessionIdentity != identity) {
            sessionIdentity = identity
            mutableState.value = NearbyChatState()
        }
    }

    @Synchronized
    fun clear() {
        sessionIdentity = null
        mutableState.value = NearbyChatState()
    }

    @Synchronized
    internal fun sending(value: Boolean) {
        mutableState.value = mutableState.value.copy(sending = value, error = null)
    }

    @Synchronized
    internal fun failed(message: String) {
        mutableState.value = mutableState.value.copy(sending = false, error = message.take(240))
    }

    @Synchronized
    internal fun sent(senderName: String, body: String) {
        append(senderName, validate(body), outgoing = true)
    }

    @Synchronized
    internal fun received(senderName: String, body: String) {
        append(senderName, validate(body), outgoing = false)
    }

    @Synchronized
    private fun append(senderName: String, body: String, outgoing: Boolean) {
        val safeName = senderName.trim().take(NearbyPairing.MAX_NAME_LENGTH).ifBlank { "Telefonas" }
        val next = NearbyChatMessage(UUID.randomUUID().toString(), safeName, body, outgoing, System.currentTimeMillis())
        mutableState.value = mutableState.value.copy(
            messages = (mutableState.value.messages + next).takeLast(MAX_VISIBLE_MESSAGES),
            sending = false,
            error = null,
        )
    }

    fun validate(raw: String): String {
        val text = raw.trim()
        require(text.isNotEmpty()) { "Žinutė tuščia" }
        require(text.length <= MAX_MESSAGE_CHARACTERS) { "Žinutė per ilga" }
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_MESSAGE_BYTES) { "Žinutė per ilga" }
        require(text.none { it == '\u0000' || (it.isISOControl() && it !in "\n\r\t") }) { "Žinutėje yra nepalaikomų ženklų" }
        return text
    }
}
