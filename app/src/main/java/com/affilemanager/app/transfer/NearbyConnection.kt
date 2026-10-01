package com.affilemanager.app.transfer

/** One process-local pairing. Nothing here is written to preferences, logs, or saved UI state. */
internal class NearbyConnection(private val now: () -> Long = System::currentTimeMillis) {
    private val mutablePeer = kotlinx.coroutines.flow.MutableStateFlow<NearbyPairing?>(null)
    val state: kotlinx.coroutines.flow.StateFlow<NearbyPairing?> = mutablePeer
    private var peer: NearbyPairing? = null
    private var cookie: String? = null
    private var expiresAt = 0L
    private var queuedMetadata = false
    private data class GroupSession(val cookie: String, val expires: Long, val supportsQueue: Boolean)
    private var groupPeers: Set<NearbyPairing> = emptySet()
    private val groupSessions = linkedMapOf<NearbyPairing, GroupSession>()
    @Synchronized fun beginGroup(peers: List<NearbyPairing>) {
        require(peers.size in 1..9)
        groupPeers = peers.toSet()
        groupSessions.keys.retainAll(groupPeers)
        expire()
        val current = peer
        val accepted = cookie
        if (current in groupPeers && accepted != null) groupSessions[requireNotNull(current)] = GroupSession(accepted, expiresAt, queuedMetadata)
    }
    @Synchronized fun supportsQueue(pairing: NearbyPairing): Boolean { expire(); return groupSessions[pairing]?.supportsQueue ?: (peer == pairing && queuedMetadata) }
    @Synchronized fun setQueueSupported(pairing: NearbyPairing, supported: Boolean) {
        if (peer == pairing) queuedMetadata = supported
        groupSessions[pairing]?.let { groupSessions[pairing] = it.copy(supportsQueue = supported) }
    }

    @Synchronized fun pairing(): NearbyPairing? { expire(); return peer }
    @Synchronized fun cookieFor(pairing: NearbyPairing): String? {
        expire()
        return groupSessions[pairing]?.cookie ?: cookie.takeIf { peer == pairing }
    }
    @Synchronized fun remember(pairing: NearbyPairing, sessionCookie: String? = null, expires: Long = now() + 15 * 60_000L) {
        if (pairing !in groupPeers) { groupPeers = emptySet(); groupSessions.clear() }
        if (pairing != peer) { cookie = null; queuedMetadata = false }
        peer = pairing
        if (sessionCookie != null) {
            require(sessionCookie.length <= 256 && sessionCookie.startsWith("af_session=") && sessionCookie.none(Char::isISOControl))
            cookie = sessionCookie
        }
        val maximumTimedExpiry = Math.addExact(now(), LanSessionDuration.MAX_TIMED_MINUTES * 60_000L)
        expiresAt = if (expires == LanSessionDuration.MANUAL_EXPIRY) {
            LanSessionDuration.MANUAL_EXPIRY
        } else {
            minOf(expires, maximumTimedExpiry)
        }
        mutablePeer.value = peer
        if (pairing in groupPeers && cookie != null) groupSessions[pairing] = GroupSession(requireNotNull(cookie), expiresAt, queuedMetadata)
    }
    @Synchronized fun clear() { peer = null; cookie = null; expiresAt = 0L; queuedMetadata = false; mutablePeer.value = null; groupPeers = emptySet(); groupSessions.clear() }
    @Synchronized fun clearIfRejected(pairing: NearbyPairing, httpCode: Int): Boolean {
        if (httpCode !in setOf(401, 403, 410)) return false
        groupSessions.remove(pairing)
        if (peer == pairing) { peer = null; cookie = null; expiresAt = 0L; queuedMetadata = false; mutablePeer.value = null }
        return true
    }
    private fun expire() {
        val currentTime = now()
        groupSessions.entries.removeAll { LanSessionDuration.isExpired(currentTime, it.value.expires) }
        if (LanSessionDuration.isExpired(currentTime, expiresAt)) {
            peer = null; cookie = null; expiresAt = 0L; queuedMetadata = false; mutablePeer.value = null
        }
    }
}
