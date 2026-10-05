package com.affilemanager.app.transfer

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

internal object LanTestNetwork {
    fun privateAddress(): InetAddress = selectPrivateAddress(
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() },
        ::acceptedSource,
    )

    fun selectPrivateAddress(
        candidates: List<InetAddress>,
        observedSource: (InetAddress) -> InetAddress,
    ): InetAddress = candidates.asSequence()
        .filter { it is Inet4Address && it.isSiteLocalAddress }
        .take(8)
        .firstOrNull { candidate ->
            // A container bridge may SNAT even an explicitly source-bound socket.
            // Use a real interface with stable identity, not a weaker server check.
            runCatching { observedSource(candidate).hostAddress == candidate.hostAddress }.getOrDefault(false)
        } ?: error("No private IPv4 test interface preserves its accepted source address")

    private fun acceptedSource(address: InetAddress): InetAddress = ServerSocket(0, 1, address).use { listener ->
        listener.soTimeout = 5_000
        Socket().use { client ->
            client.bind(InetSocketAddress(address, 0))
            client.connect(InetSocketAddress(address, listener.localPort), 5_000)
            listener.accept().use { accepted -> accepted.inetAddress }
        }
    }
}
