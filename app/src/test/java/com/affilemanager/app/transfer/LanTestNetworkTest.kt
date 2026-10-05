package com.affilemanager.app.transfer

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LanTestNetworkTest {
    private val bridge = InetAddress.getByName("172.17.0.1")
    private val stable = InetAddress.getByName("10.1.0.173")

    @Test fun skipsARewrittenBridgeAndUsesTheVerifiedPrivateInterface() {
        val probed = mutableListOf<InetAddress>()
        val chosen = LanTestNetwork.selectPrivateAddress(listOf(bridge, stable)) { address ->
            probed += address
            stable
        }
        assertEquals(stable, chosen)
        assertEquals(listOf(bridge, stable), probed)
    }

    @Test fun neverUsesAPublicOrLoopbackPairingAddress() {
        val probed = mutableListOf<InetAddress>()
        val chosen = LanTestNetwork.selectPrivateAddress(listOf(
            InetAddress.getByName("198.51.100.1"), InetAddress.getByName("127.0.0.1"), stable,
        )) { address -> probed += address; address }
        assertEquals(stable, chosen)
        assertEquals(listOf(stable), probed)
    }

    @Test fun failsRatherThanSkippingTestsWhenEverySourceIsRewritten() {
        assertThrows(IllegalStateException::class.java) {
            LanTestNetwork.selectPrivateAddress(listOf(bridge)) { stable }
        }
    }

    @Test fun aFailedProbeDoesNotHideFailureWhenNoUsableInterfaceRemains() {
        assertThrows(IllegalStateException::class.java) {
            LanTestNetwork.selectPrivateAddress(listOf(stable)) { throw java.net.SocketTimeoutException() }
        }
    }
}
