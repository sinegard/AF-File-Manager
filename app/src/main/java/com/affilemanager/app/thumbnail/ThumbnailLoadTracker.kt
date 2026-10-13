package com.affilemanager.app.thumbnail

import java.util.concurrent.atomic.AtomicBoolean

/** Only the two permitted active decodes; unrelated copies must not cancel thumbnail loads. */
internal class ThumbnailLoadTracker {
    class Ticket(val source: String) {
        private val current = AtomicBoolean(true)
        val isCurrent: Boolean get() = current.get()
        internal fun invalidate() { current.set(false) }
    }
    private val active = hashSetOf<Ticket>()

    @Synchronized fun begin(source: String): Ticket {
        check(active.size < 2) { "AF-THUMB-ACTIVE-LIMIT" }
        return Ticket(source).also(active::add)
    }

    @Synchronized fun finish(ticket: Ticket) { active.remove(ticket) }
    @Synchronized fun invalidate(affected: (String) -> Boolean) {
        active.filter { affected(it.source) }.forEach(Ticket::invalidate)
    }
}
