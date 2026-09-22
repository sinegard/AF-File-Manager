package com.affilemanager.app.transfer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.Locale
import java.util.UUID

enum class QuickTunnelStatus { STOPPED, STARTING, RUNNING, ERROR }

data class QuickTunnelState(
    val status: QuickTunnelStatus = QuickTunnelStatus.STOPPED,
    val publicUrl: String? = null,
    val message: String? = null,
    val requestId: String? = null,
)

internal object QuickTunnelRules {
    fun loopbackOrigin(lanUrl: String): String {
        val parsed = runCatching { URI(lanUrl.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("Vietinio Web serverio adresas netinkamas")
        require(parsed.scheme.equals("http", ignoreCase = true)) { "Tuneliui reikia veikiančios Web sesijos" }
        require(parsed.userInfo == null && parsed.query == null && parsed.fragment == null) {
            "Vietinio Web serverio adresas netinkamas"
        }
        require(parsed.port in 1..65_535) { "Vietinio Web serverio prievadas netinkamas" }
        return "http://127.0.0.1:${parsed.port}"
    }

    fun expiryMillis(expiresAtMillis: Long?, nowMillis: Long): Long = expiresAtMillis
        ?.takeIf { it > nowMillis }
        ?.coerceAtMost(nowMillis + MAX_TUNNEL_DURATION_MILLIS)
        ?: 0L

    const val MAX_TUNNEL_DURATION_MILLIS = 24L * 60L * 60L * 1_000L
}

internal class QuickTunnelOutputParser {
    private var publicUrl: String? = null
    private var registered = false
    private var announced = false

    fun accept(line: String): String? {
        PUBLIC_URL.find(line)?.value?.let { publicUrl = it.lowercase(Locale.ROOT) }
        if (line.contains("Registered tunnel connection", ignoreCase = true)) registered = true
        if (announced || !registered) return null
        return publicUrl?.also { announced = true }
    }

    private companion object {
        val PUBLIC_URL = Regex(
            "https://[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?\\.trycloudflare\\.com",
            RegexOption.IGNORE_CASE,
        )
    }
}

object QuickTunnelController {
    private val mutableState = MutableStateFlow(QuickTunnelState())
    val state: StateFlow<QuickTunnelState> = mutableState.asStateFlow()

    fun initialize(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    fun start(context: Context, lanUrl: String, expiresAtMillis: Long?) {
        val requestId = UUID.randomUUID().toString()
        val origin = runCatching { QuickTunnelRules.loopbackOrigin(lanUrl) }.getOrElse { error ->
            mutableState.value = QuickTunnelState(
                status = QuickTunnelStatus.ERROR,
                message = error.message ?: "Tunelio paleisti nepavyko",
            )
            return
        }
        mutableState.value = QuickTunnelState(
            status = QuickTunnelStatus.STARTING,
            message = "Kuriama laikina vieša nuoroda",
            requestId = requestId,
        )
        val intent = Intent(context, QuickTunnelService::class.java)
            .setAction(QuickTunnelService.ACTION_START)
            .putExtra(QuickTunnelService.EXTRA_ORIGIN, origin)
            .putExtra(
                QuickTunnelService.EXTRA_EXPIRES_AT,
                QuickTunnelRules.expiryMillis(expiresAtMillis, System.currentTimeMillis()),
            )
            .putExtra(QuickTunnelService.EXTRA_REQUEST_ID, requestId)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { error -> publishError(requestId, error.message ?: "Tunelio paleisti nepavyko") }
    }

    fun stop(context: Context) {
        runCatching {
            context.startService(
                Intent(context, QuickTunnelService::class.java).setAction(QuickTunnelService.ACTION_STOP),
            )
        }
        mutableState.value = QuickTunnelState()
    }

    internal fun publishStarting(requestId: String) {
        if (accepts(requestId)) {
            mutableState.value = QuickTunnelState(
                status = QuickTunnelStatus.STARTING,
                message = "Kuriama laikina vieša nuoroda",
                requestId = requestId,
            )
        }
    }

    internal fun publishRunning(requestId: String, publicUrl: String) {
        if (accepts(requestId)) {
            mutableState.value = QuickTunnelState(
                status = QuickTunnelStatus.RUNNING,
                publicUrl = publicUrl.take(256),
                message = "Quick Tunnel veikia",
                requestId = requestId,
            )
        }
    }

    internal fun publishError(requestId: String?, message: String) {
        if (requestId == null || accepts(requestId)) {
            mutableState.value = QuickTunnelState(
                status = QuickTunnelStatus.ERROR,
                message = message.take(300),
                requestId = requestId,
            )
        }
    }

    internal fun publishStopped() {
        mutableState.value = QuickTunnelState()
    }

    private fun accepts(requestId: String): Boolean = mutableState.value.requestId.let { current ->
        current == null || current == requestId
    }
}
