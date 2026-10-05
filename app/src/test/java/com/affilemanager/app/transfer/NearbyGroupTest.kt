package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyGroupTest {
    @Test fun delayedLoginCannotResurrectALeftGroupOrOverwriteANewHostSession() {
        val address = LanTestNetwork.privateAddress()
        val accepted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        java.net.ServerSocket(0, 2, address).use { server ->
            val worker = Thread {
                try {
                    server.soTimeout = 3_000
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        assertTrue(reader.readLine().startsWith("POST /login "))
                        while (reader.readLine().isNotEmpty()) { /* Bounded fixture request. */ }
                        accepted.countDown()
                        check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n" +
                            "Set-Cookie: af_session=private-test; Path=/\r\nConnection: close\r\n\r\nOK").toByteArray())
                        socket.getOutputStream().flush()
                    }
                    server.soTimeout = 500
                    try { server.accept().use { throw AssertionError("Stale join continued after leaving") } }
                    catch (_: java.net.SocketTimeoutException) { /* No stale join request. */ }
                } catch (error: Throwable) { failure.set(error) }
                finally { finished.countDown() }
            }.apply { isDaemon = true; start() }
            try {
                val invite = NearbyGroupInvite(NearbyPairing.create(address.hostAddress, server.localPort, "12345678", "Old host"), "Old group")
                NearbyGroupController.join(invite, pairing(2))
                assertTrue(accepted.await(3, java.util.concurrent.TimeUnit.SECONDS))
                NearbyGroupController.leave()
                assertEquals(NearbyGroupStatus.IDLE, NearbyGroupController.state.value.status)
                NearbyGroupController.host(NearbyGroupInvite(pairing(3), "New group"))
                release.countDown()
                assertTrue(finished.await(3, java.util.concurrent.TimeUnit.SECONDS))
                failure.get()?.let { throw AssertionError("Delayed-login fixture failed", it) }
                assertEquals(NearbyGroupStatus.HOSTING, NearbyGroupController.state.value.status)
                assertEquals("New group", NearbyGroupController.state.value.groupName)
            } finally {
                release.countDown()
                NearbyGroupController.leave()
                worker.join(1_000)
            }
        }
    }

    @Test fun aStaleIdentityCannotHeartbeatOrLeaveForAReconnectedMember() {
        val directory = NearbyGroupDirectory()
        val old = pairing(1)
        val current = old.copy(code = "98765432")
        directory.join(old)
        directory.join(current)
        assertFalse(directory.heartbeat(old))
        assertFalse(directory.leave(old))
        assertFalse(directory.remove(old))
        assertEquals(current, directory.snapshot(pairing(0)).last().pairing)
        assertTrue(directory.heartbeat(current))
    }
    @Test fun messageBlockingIsIndependentReversibleAndEncodedForTheOrganizer() {
        val directory = NearbyGroupDirectory()
        val member = pairing(1)
        directory.join(member)
        assertTrue(directory.setMessagesBlocked(member, true))
        assertFalse(directory.messagesAllowed(member.host))
        assertTrue(directory.messagesAllowed(pairing(2).host))
        val encoded = NearbyGroupCodec.encode(directory.snapshot(pairing(0)))
        assertTrue(NearbyGroupCodec.decode(encoded).last().messagesBlocked)
        assertTrue(directory.setMessagesBlocked(member, false))
        assertTrue(directory.messagesAllowed(member.host))
        assertFalse(directory.setMessagesBlocked(pairing(3), true))
    }
    private fun pairing(index: Int) = NearbyPairing.create(
        host = "192.168.1.${index + 10}",
        port = 20_000 + index,
        code = (10_000_000 + index).toString(),
        receiverName = "Phone $index",
    )

    @Test
    fun pairingFeedbackOnlyFiresForANewlyConnectedPhone() {
        val organizer = NearbyGroupMember(pairing(0), true)
        val member = NearbyGroupMember(pairing(1))
        val hosting = NearbyGroupState(status = NearbyGroupStatus.HOSTING, members = listOf(organizer))
        val connected = hosting.copy(members = listOf(organizer, member))
        assertTrue(groupPairingConfirmed(hosting, connected))
        assertFalse(groupPairingConfirmed(connected, connected))
        assertFalse(groupPairingConfirmed(connected, hosting))
        assertTrue(groupPairingConfirmed(NearbyGroupState(status = NearbyGroupStatus.JOINING),
            NearbyGroupState(status = NearbyGroupStatus.JOINED, members = listOf(organizer, member))))
    }

    @Test
    fun inviteRoundTripsWithoutChangingPairing() {
        val invite = NearbyGroupInvite(pairing(1), "Family phones")

        assertEquals(invite, NearbyGroupInvite.parse(invite.encoded()))
    }

    @Test
    fun directoryKeepsOrganizerAndAtMostNineOtherPhones() {
        var now = 1_000L
        val directory = NearbyGroupDirectory { now }
        val organizer = pairing(0)
        repeat(9) { assertTrue(directory.join(pairing(it + 1))) }

        val snapshot = directory.snapshot(organizer)

        assertEquals(10, snapshot.size)
        assertTrue(snapshot.first().organizer)
        assertThrows(IllegalArgumentException::class.java) { directory.join(pairing(20)) }
        assertFalse(directory.join(pairing(1)))
    }

    @Test
    fun organizerRemovalRevokesRejoinForCurrentGroupSession() {
        val directory = NearbyGroupDirectory()
        val organizer = pairing(0)
        val member = pairing(1)
        assertTrue(directory.join(member))
        assertTrue(directory.remove(member))
        assertFalse(directory.remove(member))
        assertEquals(listOf(NearbyGroupMember(organizer, true)), directory.snapshot(organizer))
        assertFalse(directory.heartbeat(member))
        assertThrows(IllegalArgumentException::class.java) { directory.join(member) }
        assertThrows(IllegalArgumentException::class.java) {
            directory.join(member.copy(port = member.port + 1))
        }
        assertTrue(directory.join(member.copy(port = member.port + 2, code = "33333333")))
    }

    @Test
    fun staleMembersDisappearAndCodecIsBounded() {
        var now = 1_000L
        val directory = NearbyGroupDirectory { now }
        val organizer = pairing(0)
        directory.join(pairing(1))
        now += 31_000L

        assertEquals(listOf(NearbyGroupMember(organizer, organizer = true)), directory.snapshot(organizer))

        val members = listOf(NearbyGroupMember(organizer, true), NearbyGroupMember(pairing(2)))
        assertEquals(members, NearbyGroupCodec.decode(NearbyGroupCodec.encode(members)))
    }

    @Test
    fun rejectsNonGroupPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            NearbyGroupInvite.parse(pairing(1).encoded())
        }
    }

    @Test
    fun organizerGetsOneVisibleNoticeWhenANewPhoneJoins() {
        val invite = NearbyGroupInvite(pairing(0), "Family phones")
        val organizer = NearbyGroupMember(invite.organizer, organizer = true)
        val member = NearbyGroupMember(pairing(1))
        try {
            NearbyGroupController.host(invite)
            NearbyGroupController.hostMembers(invite, listOf(organizer, member))

            assertEquals("Phone 1 prisijungė prie grupės", NearbyGroupController.state.value.memberNotice)
            NearbyGroupController.clearMemberNotice("Phone 1 prisijungė prie grupės")
            NearbyGroupController.hostMembers(invite, listOf(organizer, member))
            assertNull(NearbyGroupController.state.value.memberNotice)
            NearbyGroupController.hostMembers(invite, listOf(organizer))
            assertEquals("Phone 1 paliko grupę", NearbyGroupController.state.value.memberNotice)
        } finally {
            NearbyGroupController.leave()
        }
    }
}
