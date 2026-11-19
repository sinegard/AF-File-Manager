package com.affilemanager.app.transfer

import java.security.SecureRandom
import java.util.UUID

/** Public UI data only. Neither the login code, pending ticket nor cookie is exposed here. */
data class NearbySenderRequest(val id: String, val name: String, val address: String)

/** One receive session's volatile authority. A login is NOT an authorization to write. */
internal class NearbySenderApproval(
    private val maximumApproved: Int,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onChanged: () -> Unit = {},
) {
    companion object {
        const val MAX_PENDING = 4
        const val MAX_RECORDS = 16
        const val PENDING_MILLIS = 60_000L
        private const val TERMINAL_MILLIS = 15_000L
        private val random = SecureRandom()
        fun clientId(): String = UUID.randomUUID().toString().replace("-", "")
        private fun secret(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    enum class Status { PENDING, APPROVED, REJECTED, EXPIRED, CANCELLED, FULL }
    class Reply(val status: Status, val ticket: String? = null, val cookie: String? = null) {
        override fun toString(): String = "SenderApproval($status)"
    }
    private class Entry(val view: NearbySenderRequest, val client: String, val ticket: String, var deadline: Long,
                        var status: Status = Status.PENDING, var cookie: String? = null)
    private val entries = linkedMapOf<String, Entry>()
    private val tickets = hashMapOf<String, Entry>()
    private val clients = hashMapOf<String, Entry>()
    private val cookies = hashMapOf<String, Entry>()
    private var active = true
    init { require(maximumApproved in 1..10) }

    @Synchronized fun request(address: String, clientId: String, name: String): Reply {
        refresh()
        if (!active) return Reply(Status.EXPIRED)
        require(address.length in 1..64 && address.none(Char::isISOControl))
        require(clientId.matches(Regex("[a-f0-9]{32}")))
        require(name.length in 1..64 && name.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' })
        val key = "$address/$clientId"
        clients[key]?.let { return reply(it) } // Retries never rename or extend an owner's pending decision.
        if (cookies.size >= maximumApproved || entries.values.count { it.status == Status.PENDING } >= MAX_PENDING) return Reply(Status.FULL)
        if (entries.size >= MAX_RECORDS) entries.values.firstOrNull { it.status !in setOf(Status.PENDING, Status.APPROVED) }?.let(::remove)
        if (entries.size >= MAX_RECORDS) return Reply(Status.FULL)
        val view = NearbySenderRequest(UUID.randomUUID().toString(), name, address)
        val entry = Entry(view, key, secret(), now() + PENDING_MILLIS)
        entries[view.id] = entry; tickets[entry.ticket] = entry; clients[key] = entry
        onChanged()
        return reply(entry)
    }

    @Synchronized fun poll(ticket: String, address: String): Reply {
        refresh()
        val entry = tickets[ticket]?.takeIf { active && it.view.address == address } ?: return Reply(Status.EXPIRED)
        return reply(entry)
    }

    @Synchronized fun cancel(ticket: String, address: String, onRevoke: (String) -> Unit = {}): Boolean {
        refresh()
        val entry = tickets[ticket]?.takeIf { active && it.view.address == address && it.status in setOf(Status.PENDING, Status.APPROVED) } ?: return false
        entry.cookie?.let { cookies.remove(it); onRevoke(it) }
        entry.cookie = null
        entry.status = Status.CANCELLED; entry.deadline = now() + TERMINAL_MILLIS
        onChanged(); return true
    }

    /** Only the non-exported service/local owner UI calls this; never a network endpoint. */
    @Synchronized fun decide(id: String, allow: Boolean, beforeGrant: (String, String) -> Unit = { _, _ -> }): Boolean {
        refresh()
        val entry = entries[id]?.takeIf { active && it.status == Status.PENDING } ?: return false
        if (!allow || cookies.size >= maximumApproved) {
            entry.status = Status.REJECTED; entry.deadline = now() + TERMINAL_MILLIS
        } else {
            val cookie = secret()
            beforeGrant(cookie, entry.view.address)
            entry.cookie = cookie; entry.status = Status.APPROVED; cookies[cookie] = entry
            if (maximumApproved == 1) entries.values.filter { it !== entry && it.status == Status.PENDING }.forEach {
                it.status = Status.REJECTED; it.deadline = now() + TERMINAL_MILLIS
            }
        }
        onChanged(); return true
    }

    @Synchronized fun authenticated(cookie: String, address: String): Boolean =
        active && cookies[cookie]?.let { it.status == Status.APPROVED && it.view.address == address } == true

    @Synchronized fun revoke(cookie: String) { cookies[cookie]?.let(::remove); onChanged() }
    @Synchronized fun pending(): List<NearbySenderRequest> = entries.values.filter { it.status == Status.PENDING }.map { it.view }
    @Synchronized fun refresh() {
        val time = now()
        var changed = false
        entries.values.toList().forEach { entry ->
            if (entry.status == Status.PENDING && time >= entry.deadline) {
                entry.status = Status.EXPIRED; entry.deadline = time + TERMINAL_MILLIS; changed = true
            } else if (entry.status !in setOf(Status.PENDING, Status.APPROVED) && time >= entry.deadline) remove(entry)
        }
        if (changed) onChanged()
    }
    @Synchronized fun close() { active = false; entries.clear(); tickets.clear(); clients.clear(); cookies.clear(); onChanged() }
    private fun reply(entry: Entry) = Reply(entry.status, entry.ticket, entry.cookie.takeIf { entry.status == Status.APPROVED })
    private fun remove(entry: Entry) { entries.remove(entry.view.id); tickets.remove(entry.ticket); clients.remove(entry.client); entry.cookie?.let(cookies::remove) }
}
