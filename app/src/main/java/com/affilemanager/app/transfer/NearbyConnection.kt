package com.affilemanager.app.transfer

/** One process-local pairing. Nothing here is written to preferences, logs, or saved UI state. */
internal class NearbyConnection(private val now: () -> Long = System::currentTimeMillis) {
    private var peer: NearbyPairing? = null
    private var cookie: String? = null
    private var expiresAt = 0L

    @Synchronized fun pairing(): NearbyPairing? { expire(); return peer }
    @Synchronized fun cookieFor(pairing: NearbyPairing): String? {
        expire()
        return cookie.takeIf { peer == pairing }
    }
    @Synchronized fun remember(pairing: NearbyPairing, sessionCookie: String? = null, expires: Long = now() + 15 * 60_000L) {
        if (pairing != peer) cookie = null
        peer = pairing
        if (sessionCookie != null) {
            require(sessionCookie.length <= 256 && sessionCookie.startsWith("af_session=") && sessionCookie.none(Char::isISOControl))
            cookie = sessionCookie
        }
        expiresAt = minOf(expires, now() + 15 * 60_000L)
    }
    @Synchronized fun clear() { peer = null; cookie = null; expiresAt = 0L }
    @Synchronized fun clearIfRejected(pairing: NearbyPairing, httpCode: Int): Boolean {
        if (httpCode !in setOf(401, 403, 410)) return false
        if (peer == pairing) clear()
        return true
    }
    private fun expire() { if (now() >= expiresAt) clear() }
}
