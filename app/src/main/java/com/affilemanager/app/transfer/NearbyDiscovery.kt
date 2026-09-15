package com.affilemanager.app.transfer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NearbyDiscoveredDevice(
    val serviceName: String,
    val receiverName: String,
    val deviceName: String,
    val pairing: NearbyPairing,
)

data class NearbyDiscoveryState(
    val searching: Boolean = false,
    val devices: List<NearbyDiscoveredDevice> = emptyList(),
    val message: String? = null,
)

internal object NearbyDiscoveryPayload {
    const val SERVICE_TYPE = "_affilemanager._tcp."
    const val MAX_RESULTS = 20
    private const val MAX_ATTRIBUTE_BYTES = 128

    fun attributes(pairing: NearbyPairing, receiverName: String, deviceName: String): Map<String, ByteArray> = mapOf(
        "v" to "1".toByteArray(StandardCharsets.UTF_8),
        "name" to clean(receiverName, NearbyPairing.MAX_NAME_LENGTH).toByteArray(StandardCharsets.UTF_8),
        "device" to clean(deviceName, NearbyPairing.MAX_NAME_LENGTH).toByteArray(StandardCharsets.UTF_8),
        "host" to pairing.host.toByteArray(StandardCharsets.US_ASCII),
        "code" to pairing.code.toByteArray(StandardCharsets.UTF_8),
    )

    fun decode(
        serviceName: String,
        port: Int,
        resolvedHost: String?,
        attributes: Map<String, ByteArray>,
    ): NearbyDiscoveredDevice? = runCatching {
        require(text(attributes, "v") == "1") { "Nepalaikoma radimo versija" }
        val receiver = clean(text(attributes, "name"), NearbyPairing.MAX_NAME_LENGTH)
        val device = clean(text(attributes, "device"), NearbyPairing.MAX_NAME_LENGTH)
        val advertisedHost = text(attributes, "host")
        val host = sequenceOf(resolvedHost, advertisedHost)
            .filterNotNull()
            .firstOrNull(NearbyPairing::isPrivateIpv4)
            ?: error("Privatus gavėjo adresas nerastas")
        val pairing = NearbyPairing.create(host, port, text(attributes, "code"), receiver)
        NearbyDiscoveredDevice(
            serviceName = clean(serviceName, NearbyPairing.MAX_NAME_LENGTH),
            receiverName = receiver,
            deviceName = device.ifBlank { "Android" },
            pairing = pairing,
        )
    }.getOrNull()

    private fun text(values: Map<String, ByteArray>, key: String): String {
        val bytes = values[key] ?: error("Trūksta radimo duomenų")
        require(bytes.size in 1..MAX_ATTRIBUTE_BYTES) { "Radimo duomenys per ilgi" }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun clean(value: String, maximum: Int): String = value
        .filterNot(Char::isISOControl)
        .trim()
        .take(maximum)
}

/** UI-owned mDNS advertisement. Nothing is persisted and closing the receive dialog unregisters it. */
class NearbyDeviceAdvertiser(context: Context, private val onError: (String) -> Unit = {}) : Closeable {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(pairing: NearbyPairing, receiverName: String) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = "AF ${receiverName.filterNot(Char::isISOControl).trim().take(40).ifBlank { "Phone" }}"
            serviceType = NearbyDiscoveryPayload.SERVICE_TYPE
            port = pairing.port
            NearbyDiscoveryPayload.attributes(pairing, receiverName, deviceLabel()).forEach { (key, value) ->
                setAttribute(key, value.toString(StandardCharsets.UTF_8))
            }
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                onError("Artimų įrenginių rodymo paleisti nepavyko ($errorCode)")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = registration
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
            .onFailure {
                listener = null
                onError("Artimų įrenginių rodymo paleisti nepavyko")
            }
    }

    @Synchronized
    fun stop() {
        val current = listener ?: return
        listener = null
        runCatching { manager.unregisterService(current) }
    }

    override fun close() = stop()

    private fun deviceLabel(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString(" ")
        .ifBlank { "Android" }
}

/** One bounded, dialog-owned mDNS browse operation with serialized legacy resolution. */
class NearbyDeviceDiscovery(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val mutableState = MutableStateFlow(NearbyDiscoveryState())
    val state: StateFlow<NearbyDiscoveryState> = mutableState.asStateFlow()
    private val lock = Any()
    private data class PendingResolution(val info: NsdServiceInfo, val generation: Int)
    private val pending = ArrayDeque<PendingResolution>()
    @Volatile
    private var active = false
    @Volatile
    private var generation = 0
    private var resolving = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (active) return
        active = true
        generation += 1
        val currentGeneration = generation
        mutableState.value = NearbyDiscoveryState(searching = true)
        multicastLock = runCatching {
            wifi.createMulticastLock("af-nearby-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (isCurrent(currentGeneration)) {
                    mutableState.value = mutableState.value.copy(searching = true, message = null)
                }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) = enqueue(serviceInfo, currentGeneration)
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (isCurrent(currentGeneration)) {
                    mutableState.value = mutableState.value.copy(
                        devices = mutableState.value.devices.filterNot { it.serviceName == serviceInfo.serviceName },
                    )
                }
            }
            override fun onDiscoveryStopped(serviceType: String) {
                if (isCurrent(currentGeneration)) mutableState.value = mutableState.value.copy(searching = false)
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                fail("Artimų įrenginių paieška nepavyko ($errorCode)", currentGeneration)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (isCurrent(currentGeneration)) mutableState.value = mutableState.value.copy(searching = false)
            }
        }
        listener = discovery
        runCatching { manager.discoverServices(NearbyDiscoveryPayload.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { fail("Artimų įrenginių paieška nepavyko", currentGeneration) }
    }

    private fun enqueue(info: NsdServiceInfo, sourceGeneration: Int) {
        synchronized(lock) {
            if (!isCurrent(sourceGeneration) ||
                info.serviceType.trimEnd('.') != NearbyDiscoveryPayload.SERVICE_TYPE.trimEnd('.')) return
            if (pending.size >= NearbyDiscoveryPayload.MAX_RESULTS) return
            if (pending.any { it.info.serviceName == info.serviceName }) return
            if (mutableState.value.devices.any { it.serviceName == info.serviceName }) return
            pending.addLast(PendingResolution(info, sourceGeneration))
            resolveNextLocked()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNextLocked() {
        if (!active || resolving) return
        if (pending.isEmpty()) return
        val next = pending.removeFirst()
        if (!isCurrent(next.generation)) return resolveNextLocked()
        resolving = true
        runCatching {
            manager.resolveService(next.info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = resolved(null, next.generation)
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) = resolved(serviceInfo, next.generation)
            })
        }.onFailure { resolved(null, next.generation) }
    }

    @Suppress("DEPRECATION")
    private fun resolved(info: NsdServiceInfo?, sourceGeneration: Int) {
        synchronized(lock) {
            if (!isCurrent(sourceGeneration)) return
            if (info != null) {
                NearbyDiscoveryPayload.decode(
                    serviceName = info.serviceName,
                    port = info.port,
                    resolvedHost = info.host?.hostAddress,
                    attributes = info.attributes,
                )?.let { device ->
                    val updated = (mutableState.value.devices.filterNot { it.serviceName == device.serviceName } + device)
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.receiverName })
                        .take(NearbyDiscoveryPayload.MAX_RESULTS)
                    mutableState.value = mutableState.value.copy(devices = updated, message = null)
                }
            }
            resolving = false
            resolveNextLocked()
        }
    }

    @Synchronized
    private fun fail(message: String, sourceGeneration: Int) {
        if (isCurrent(sourceGeneration)) {
            mutableState.value = mutableState.value.copy(searching = false, message = message.take(200))
        }
    }

    private fun isCurrent(sourceGeneration: Int): Boolean = active && generation == sourceGeneration

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        generation += 1
        listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        listener = null
        synchronized(lock) {
            pending.clear()
            resolving = false
        }
        runCatching { multicastLock?.release() }
        multicastLock = null
        mutableState.value = mutableState.value.copy(searching = false)
    }

    override fun close() = stop()
}
