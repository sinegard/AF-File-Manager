package com.affilemanager.app.transfer

import org.junit.Assert.*
import org.junit.Test

class NearbySenderApprovalTest {
    private val host = "192.168.1.20"
    private fun request(gate: NearbySenderApproval, name: String = "Owned sender") = gate.request(host, NearbySenderApproval.clientId(), name)

    @Test fun senderCancellationRevokesEvenAnApprovalThatRacedWithCancellation() {
        val gate = NearbySenderApproval(1)
        val pending = request(gate)
        assertTrue(gate.decide(gate.pending().single().id, true))
        val cookie = requireNotNull(gate.poll(pending.ticket!!, host).cookie)
        var revoked = false
        assertTrue(gate.cancel(pending.ticket, host) { token ->
            assertEquals(cookie, token)
            assertFalse(gate.authenticated(cookie, host))
            revoked = true
        })
        assertTrue(revoked)
        assertFalse(gate.authenticated(cookie, host))
        assertNull(gate.poll(pending.ticket, host).cookie)
    }

    @Test fun namesCannotInjectControlsAndTerminalFloodStaysBounded() {
        val gate = NearbySenderApproval(1)
        for (name in listOf("bad\nname", "\u202Espoof", "\u2066spoof", "x".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { request(gate, name) }
        }
        repeat(200) {
            val pending = request(gate)
            assertEquals(NearbySenderApproval.Status.PENDING, pending.status)
            assertTrue(gate.decide(gate.pending().single().id, false))
        }
        val field = NearbySenderApproval::class.java.getDeclaredField("entries").apply { isAccessible = true }
        assertTrue((field.get(gate) as Map<*, *>).size <= NearbySenderApproval.MAX_RECORDS)
    }

    @Test fun pendingDoesNotGrantAnythingAndOnlyLocalDecisionCreatesAnIpBoundCookie() {
        val gate = NearbySenderApproval(1)
        val reply = request(gate)
        assertEquals(NearbySenderApproval.Status.PENDING, reply.status)
        assertNull(reply.cookie)
        assertFalse(gate.authenticated(reply.ticket!!, host))
        assertEquals(NearbySenderApproval.Status.EXPIRED, gate.poll(reply.ticket, "192.168.1.99").status)
        assertFalse(gate.decide(reply.ticket, true)) // Private polling token is not a local decision ID.
        assertTrue(gate.decide(gate.pending().single().id, true))
        val accepted = gate.poll(reply.ticket, host)
        assertTrue(gate.authenticated(accepted.cookie!!, host))
        assertFalse(gate.authenticated(accepted.cookie, "192.168.1.99"))
        assertFalse(gate.authenticated("fabricated", host))
        assertTrue(gate.pending().isEmpty())
    }

    @Test fun rejectCancelAndTimeoutNeverAuthorizeAndCannotBeApprovedLater() {
        for (outcome in listOf("reject", "cancel", "timeout")) {
            var time = 0L
            val gate = NearbySenderApproval(1, { time })
            val reply = request(gate)
            val id = gate.pending().single().id
            when (outcome) {
                "reject" -> assertTrue(gate.decide(id, false))
                "cancel" -> assertTrue(gate.cancel(reply.ticket!!, host))
                else -> { time = NearbySenderApproval.PENDING_MILLIS; gate.refresh() }
            }
            assertNull(gate.poll(reply.ticket!!, host).cookie)
            assertFalse(gate.decide(id, true))
            assertTrue(gate.pending().isEmpty())
        }
    }

    @Test fun requestRetriesDoNotChangeTheNameOrExtendThePendingDeadline() {
        var time = 0L
        val gate = NearbySenderApproval(1, { time })
        val id = NearbySenderApproval.clientId()
        val first = gate.request(host, id, "First name")
        time = NearbySenderApproval.PENDING_MILLIS - 1
        assertEquals(first.ticket, gate.request(host, id, "Different name").ticket)
        assertEquals("First name", gate.pending().single().name)
        time++
        assertEquals(NearbySenderApproval.Status.EXPIRED, gate.poll(first.ticket!!, host).status)
    }

    @Test fun pendingRequestsAreBoundedAndSingleApprovalDoesNotApproveTheOthers() {
        val gate = NearbySenderApproval(1)
        val rows = List(NearbySenderApproval.MAX_PENDING) { request(gate) }
        assertEquals(NearbySenderApproval.Status.FULL, request(gate).status)
        assertEquals(4, gate.pending().size)
        gate.decide(gate.pending()[1].id, true)
        assertEquals(1, rows.count { gate.poll(it.ticket!!, host).status == NearbySenderApproval.Status.APPROVED })
        assertEquals(3, rows.count { gate.poll(it.ticket!!, host).status == NearbySenderApproval.Status.REJECTED })
        assertEquals(NearbySenderApproval.Status.FULL, request(gate).status)
    }

    @Test fun groupsNeedIndependentApprovalsAndRevokingOneLeavesTheOtherUnchanged() {
        val gate = NearbySenderApproval(10)
        val a = request(gate); val b = request(gate)
        gate.decide(gate.pending()[0].id, true)
        assertNull(gate.poll(b.ticket!!, host).cookie)
        gate.decide(gate.pending().single().id, true)
        val cookieA = gate.poll(a.ticket!!, host).cookie!!
        val cookieB = gate.poll(b.ticket, host).cookie!!
        gate.revoke(cookieA)
        assertFalse(gate.authenticated(cookieA, host))
        assertTrue(gate.authenticated(cookieB, host))
    }

    @Test fun closingOrStartingAnotherSessionNeverCarriesAuthorityAcross() {
        val gate = NearbySenderApproval(1)
        val reply = request(gate)
        val id = gate.pending().single().id
        gate.decide(id, true)
        val cookie = gate.poll(reply.ticket!!, host).cookie!!
        gate.close()
        assertFalse(gate.authenticated(cookie, host))
        assertFalse(gate.decide(id, true))
        assertEquals(NearbySenderApproval.Status.EXPIRED, request(gate).status)
        assertFalse(NearbySenderApproval(1).authenticated(cookie, host))
        assertFalse(reply.toString().contains(reply.ticket))
    }

    @Test fun decisionCannotPublishACookieBeforeItsDependentSessionStateIsReady() {
        val gate = NearbySenderApproval(1)
        val reply = request(gate)
        gate.decide(gate.pending().single().id, true) { cookie, address ->
            assertEquals(host, address)
            assertFalse(gate.authenticated(cookie, address))
            assertEquals(NearbySenderApproval.Status.PENDING, gate.poll(reply.ticket!!, host).status)
        }
        assertNotNull(gate.poll(reply.ticket!!, host).cookie)
    }
}
