package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyGroupTest {
    private fun pairing(index: Int) = NearbyPairing.create(
        host = "192.168.1.${index + 10}",
        port = 20_000 + index,
        code = (10_000_000 + index).toString(),
        receiverName = "Phone $index",
    )

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
        } finally {
            NearbyGroupController.leave()
        }
    }
}
