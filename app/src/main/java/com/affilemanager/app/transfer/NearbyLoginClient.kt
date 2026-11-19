package com.affilemanager.app.transfer

import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** The same bounded consent handshake is used by single transfers and group joins. */
internal object NearbyLoginClient {
    class Accepted(val cookie: String, val expires: Long, val queue: Boolean, val concurrency: String?, val capacity: String?) {
        override fun toString() = "AcceptedNearbySession"
    }

    fun login(client: OkHttpClient, pairing: NearbyPairing, name: String,
              checkCancelled: () -> Unit = {}, onCall: (Call) -> Unit = {}, onWaiting: () -> Unit = {}): Accepted {
        val base = "http://${pairing.host}:${pairing.port}"
        val safeName = name.filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
            .take(64).ifBlank { "AF File Manager" }
        var ticket: String? = null
        var accepted = false
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75)
        try {
            while (true) {
                checkCancelled()
                check(System.nanoTime() < deadline) { "Patvirtinimo užklausa nebegalioja" }
                val request = Request.Builder().url(base + if (ticket == null) "/login" else "/nearby/approval")
                    .header("User-Agent", "AF-File-Manager/Nearby")
                    .header("X-AF-Sender-Approval", "1")
                    .apply { ticket?.let { header("X-AF-Approval-Request", it) } }
                    .post(if (ticket == null) FormBody.Builder().add("code", pairing.code)
                        .add("client", NearbySenderApproval.clientId()).add("name", safeName).build() else FormBody.Builder().build())
                    .build()
                val call = client.newCall(request).also { it.timeout().timeout(15, TimeUnit.SECONDS); onCall(it) }
                call.execute().use { response ->
                    checkCancelled()
                    when (response.code) {
                        202 -> {
                            check(response.header("X-AF-Approval-State") == "PENDING") { "Gavimo sesija nepatvirtinta" }
                            val returned = response.header("X-AF-Approval-Request").orEmpty()
                            check(returned.matches(Regex("[a-f0-9]{64}")) && (ticket == null || ticket == returned)) { "Gavimo sesija nepatvirtinta" }
                            ticket = returned
                            onWaiting()
                        }
                        200 -> {
                            val cookie = response.headers.values("Set-Cookie").map { it.substringBefore(';').trim() }
                                // Session cookies are opaque. Do not break older receivers by
                                // assuming the new approval gate's private token representation.
                                .firstOrNull { it.matches(Regex("af_session=[A-Za-z0-9._~+/%=\\-]{1,256}")) }
                                ?: error("Gavimo sesija nepatvirtinta")
                            accepted = true
                            return Accepted(cookie, response.header("X-AF-Session-Expires")?.toLongOrNull()
                                ?: (System.currentTimeMillis() + 15 * 60_000L), response.header("X-AF-Queue-Version") == "1",
                                response.header("X-AF-Concurrent-Uploads"), response.header(NearbyTransferCapacity.HEADER))
                        }
                        403 -> error(if (ticket != null) "Gavėjas atmetė prisijungimą" else "Gavęs telefonas atmetė susiejimo kodą (403)")
                        410 -> error("Patvirtinimo užklausa nebegalioja")
                        429 -> error("Gavėjas šiuo metu užimtas")
                        else -> error("Gavimo sesija nepatvirtinta")
                    }
                }
                // Small cancellable waits, never on the UI thread; no occupied server worker.
                repeat(10) { checkCancelled(); Thread.sleep(100) }
            }
        } finally {
            if (!accepted && ticket != null) {
                // A cancelled group job interrupts its worker. Withdraw the request
                // with a bounded call too, then preserve the caller's interrupt state.
                val interrupted = Thread.interrupted()
                try { runCatching {
                    val cancel = Request.Builder().url("$base/nearby/approval/cancel")
                        .header("X-AF-Approval-Request", ticket).post(FormBody.Builder().build()).build()
                    client.newCall(cancel).also { it.timeout().timeout(2, TimeUnit.SECONDS) }.execute().close()
                } } finally { if (interrupted) Thread.currentThread().interrupt() }
            }
        }
    }
}
