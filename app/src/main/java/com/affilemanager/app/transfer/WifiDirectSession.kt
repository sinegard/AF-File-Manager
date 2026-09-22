@file:Suppress("DEPRECATION")

package com.affilemanager.app.transfer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.wifi.WpsInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WifiDirectStatus { IDLE, DISCOVERING, CREATING_GROUP, CONNECTING, CONNECTED, ERROR }

data class WifiDirectPeer(
    val name: String,
    val address: String,
)

data class WifiDirectState(
    val status: WifiDirectStatus = WifiDirectStatus.IDLE,
    val peers: List<WifiDirectPeer> = emptyList(),
    val groupOwnerAddress: String? = null,
    val isGroupOwner: Boolean = false,
    val message: String? = null,
)

/**
 * Owns one process-local Android Wi-Fi Direct group or peer connection.
 *
 * Wi-Fi Direct only supplies the private link. Authentication, file admission, atomic writes,
 * cancellation, and progress continue through the existing nearby-transfer HTTP protocol.
 */
object WifiDirectController {
    private enum class Role { NONE, HOST, CLIENT }

    private val mutableState = MutableStateFlow(WifiDirectState())
    val state: StateFlow<WifiDirectState> = mutableState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var applicationContext: Context? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var role = Role.NONE

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled && role != Role.NONE) fail("Wi-Fi Direct yra išjungtas")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (info?.isConnected == true) requestConnectionInfo()
                    else if (role != Role.NONE && mutableState.value.status == WifiDirectStatus.CONNECTED) {
                        fail("Wi-Fi Direct ryšys nutrūko")
                    }
                }
            }
        }
    }

    fun permissionForSdk(sdk: Int): String =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    fun permissionsForSdk(sdk: Int): Array<String> =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermission(context: Context, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        ContextCompat.checkSelfPermission(context, permissionForSdk(sdk)) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun createGroup(context: Context) = onMain {
        val app = context.applicationContext
        require(hasPermission(app)) { "Wi-Fi Direct leidimas nesuteiktas" }
        role = Role.HOST
        mutableState.value = WifiDirectState(status = WifiDirectStatus.CREATING_GROUP)
        withManager(app) { wifi, activeChannel ->
            removeExistingGroup(wifi, activeChannel) {
                wifi.createGroup(activeChannel, actionListener(
                    onSuccess = { requestConnectionInfo() },
                    fallback = "Wi-Fi Direct grupės sukurti nepavyko",
                ))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun discover(context: Context) = onMain {
        val app = context.applicationContext
        require(hasPermission(app)) { "Wi-Fi Direct leidimas nesuteiktas" }
        role = Role.CLIENT
        mutableState.value = WifiDirectState(status = WifiDirectStatus.DISCOVERING)
        withManager(app) { wifi, activeChannel ->
            removeExistingGroup(wifi, activeChannel) {
                wifi.discoverPeers(activeChannel, actionListener(
                    onSuccess = { requestPeers() },
                    fallback = "Wi-Fi Direct įrenginių paieška nepavyko",
                ))
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(context: Context, peer: WifiDirectPeer) = onMain {
        val app = context.applicationContext
        require(hasPermission(app)) { "Wi-Fi Direct leidimas nesuteiktas" }
        require(peer.address.isNotBlank()) { "Wi-Fi Direct įrenginys nepasiekiamas" }
        role = Role.CLIENT
        mutableState.value = mutableState.value.copy(
            status = WifiDirectStatus.CONNECTING,
            message = null,
        )
        withManager(app) { wifi, activeChannel ->
            val config = WifiP2pConfig().apply {
                deviceAddress = peer.address
                wps.setup = WpsInfo.PBC
                groupOwnerIntent = 0
            }
            wifi.connect(activeChannel, config, actionListener(
                onSuccess = {},
                fallback = "Prisijungti per Wi-Fi Direct nepavyko",
            ))
        }
    }

    fun stop(context: Context) = onMain {
        val app = context.applicationContext
        role = Role.NONE
        val wifi = manager
        val activeChannel = channel
        mutableState.value = WifiDirectState()
        if (wifi != null && activeChannel != null && hasPermission(app)) {
            runCatching {
                @SuppressLint("MissingPermission")
                wifi.removeGroup(activeChannel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) = Unit
                })
            }
        }
        unregister()
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val wifi = manager ?: return
        val activeChannel = channel ?: return
        val context = applicationContext ?: return
        if (!hasPermission(context)) return fail("Wi-Fi Direct leidimas nesuteiktas")
        wifi.requestPeers(activeChannel) { list ->
            val peers = list.deviceList.asSequence()
                .filter { it.status != WifiP2pDevice.UNAVAILABLE }
                .map { device ->
                    WifiDirectPeer(
                        name = device.deviceName.takeUnless(String::isBlank) ?: "Wi-Fi Direct įrenginys",
                        address = device.deviceAddress.orEmpty(),
                    )
                }
                .filter { it.address.isNotBlank() }
                .distinctBy(WifiDirectPeer::address)
                .sortedBy { it.name.lowercase() }
                .take(MAX_VISIBLE_PEERS)
                .toList()
            mutableState.value = mutableState.value.copy(peers = peers, message = null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        val wifi = manager ?: return
        val activeChannel = channel ?: return
        val context = applicationContext ?: return
        if (!hasPermission(context)) return fail("Wi-Fi Direct leidimas nesuteiktas")
        wifi.requestConnectionInfo(activeChannel) { info ->
            val address = info.groupOwnerAddress?.hostAddress
            if (info.groupFormed && !address.isNullOrBlank()) {
                mutableState.value = mutableState.value.copy(
                    status = WifiDirectStatus.CONNECTED,
                    groupOwnerAddress = address,
                    isGroupOwner = info.isGroupOwner,
                    message = null,
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun removeExistingGroup(
        wifi: WifiP2pManager,
        activeChannel: WifiP2pManager.Channel,
        after: () -> Unit,
    ) {
        wifi.requestGroupInfo(activeChannel) { group ->
            if (group == null) after()
            else wifi.removeGroup(activeChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = after()
                override fun onFailure(reason: Int) = after()
            })
        }
    }

    private fun withManager(
        context: Context,
        action: (WifiP2pManager, WifiP2pManager.Channel) -> Unit,
    ) {
        val wifi = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return fail("Šis telefonas nepalaiko Wi-Fi Direct")
        applicationContext = context
        manager = wifi
        val activeChannel = channel ?: wifi.initialize(context, Looper.getMainLooper()) {
            if (role != Role.NONE) fail("Wi-Fi Direct ryšys nutrūko")
        }.also { channel = it }
        register(context)
        action(wifi, activeChannel)
    }

    private fun register(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun unregister() {
        val context = applicationContext
        if (receiverRegistered && context != null) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
        applicationContext = null
        manager = null
        channel = null
    }

    private fun actionListener(onSuccess: () -> Unit, fallback: String) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onSuccess.invoke()
            override fun onFailure(reason: Int) = fail(failureMessage(reason, fallback))
        }

    private fun fail(message: String) {
        mutableState.value = mutableState.value.copy(status = WifiDirectStatus.ERROR, message = message)
    }

    private fun failureMessage(reason: Int, fallback: String): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Šis telefonas nepalaiko Wi-Fi Direct"
        WifiP2pManager.BUSY -> "Wi-Fi Direct šiuo metu užimtas"
        else -> fallback
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private const val MAX_VISIBLE_PEERS = 64
}
