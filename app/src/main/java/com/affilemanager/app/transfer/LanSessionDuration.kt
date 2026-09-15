package com.affilemanager.app.transfer

/** Shared duration contract for every temporary LAN endpoint. Zero means manual stop. */
object LanSessionDuration {
    const val MANUAL_MINUTES = 0
    const val MIN_TIMED_MINUTES = 5
    const val MAX_TIMED_MINUTES = 120
    const val STEP_MINUTES = 5
    const val MANUAL_EXPIRY = Long.MAX_VALUE

    fun normalize(minutes: Int): Int = when {
        minutes == MANUAL_MINUTES -> MANUAL_MINUTES
        minutes < MIN_TIMED_MINUTES -> MIN_TIMED_MINUTES
        else -> minutes.coerceAtMost(MAX_TIMED_MINUTES).let {
            (it / STEP_MINUTES) * STEP_MINUTES
        }
    }

    fun expiresAt(nowMillis: Long, minutes: Int): Long {
        val normalized = normalize(minutes)
        if (normalized == MANUAL_MINUTES) return MANUAL_EXPIRY
        return Math.addExact(nowMillis, normalized * 60_000L)
    }

    fun isManual(expiresAtMillis: Long): Boolean = expiresAtMillis == MANUAL_EXPIRY

    fun isExpired(nowMillis: Long, expiresAtMillis: Long): Boolean =
        !isManual(expiresAtMillis) && nowMillis >= expiresAtMillis
}
