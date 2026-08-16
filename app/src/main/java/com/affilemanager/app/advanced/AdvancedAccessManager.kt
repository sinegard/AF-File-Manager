package com.affilemanager.app.advanced

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.affilemanager.app.BuildConfig
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

enum class AdvancedAccessMode {
    OFF,
    AUTO,
    SHIZUKU,
    ROOT,
}

enum class AdvancedAccessBackend {
    NONE,
    SHIZUKU_SHELL,
    SHIZUKU_ROOT,
    ROOT,
}

enum class CapabilityState {
    UNAVAILABLE,
    AVAILABLE,
    GRANTED,
    DENIED,
}

internal object AdvancedAccessCapabilityRules {
    fun shizukuPermission(
        binderRunning: Boolean,
        permissionGranted: Boolean,
        permissionDenied: Boolean,
    ): CapabilityState = when {
        !binderRunning -> CapabilityState.UNAVAILABLE
        permissionGranted -> CapabilityState.GRANTED
        permissionDenied -> CapabilityState.DENIED
        else -> CapabilityState.AVAILABLE
    }

    fun canRequestRoot(permission: CapabilityState): Boolean = permission != CapabilityState.UNAVAILABLE
}

data class AdvancedAccessState(
    val selectedMode: AdvancedAccessMode = AdvancedAccessMode.OFF,
    val shizukuManagerDetected: Boolean = false,
    val shizukuRunning: Boolean = false,
    val shizukuPermission: CapabilityState = CapabilityState.UNAVAILABLE,
    val rootPermission: CapabilityState = CapabilityState.UNAVAILABLE,
    val activeBackend: AdvancedAccessBackend = AdvancedAccessBackend.NONE,
    val serviceUid: Int? = null,
    val connecting: Boolean = false,
    val androidDataAccessible: Boolean? = null,
    val error: String? = null,
) {
    val connected: Boolean get() = activeBackend != AdvancedAccessBackend.NONE
}

class AdvancedAccessManager(context: Context) {
    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SHIZUKU_PERMISSION_REQUEST = 7301
        private const val PREFERENCES = "advanced_access"
        private const val MODE_KEY = "selected_mode"
        private const val CONNECTION_TIMEOUT_MILLIS = 15_000L

        init {
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setTimeout(12),
            )
        }
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val shizukuArgs = Shizuku.UserServiceArgs(ComponentName(appContext, ShizukuFileService::class.java))
        .daemon(false)
        .tag("af_privileged_files_v1")
        .version(BuildConfig.VERSION_CODE)
        .debuggable(BuildConfig.DEBUG)
        .processNameSuffix("af_privileged")

    private val _state = MutableStateFlow(
        AdvancedAccessState(selectedMode = loadMode()),
    )
    val state: StateFlow<AdvancedAccessState> = _state.asStateFlow()

    @Volatile
    private var fileSystem: FileSystemManager? = null
    private var shizukuBound = false
    private var shizukuBinding = false
    private var rootBound = false
    private var rootBinding = false
    private var bindingAttempt = 0L

    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuBinding = false
            bindingAttempt += 1
            runCatching {
                val privileged = IPrivilegedFileService.Stub.asInterface(requireNotNull(service))
                val uid = privileged.processUid
                require(uid == 0 || uid == 2_000) { "Netikėtas Shizuku tarnybos UID: $uid" }
                val remote = FileSystemManager.getRemote(privileged.fileSystemService)
                fileSystem = remote
                shizukuBound = true
                _state.update {
                    it.copy(
                        activeBackend = if (uid == 0) AdvancedAccessBackend.SHIZUKU_ROOT else AdvancedAccessBackend.SHIZUKU_SHELL,
                        serviceUid = uid,
                        connecting = false,
                        error = null,
                    )
                }
            }.onFailure { error ->
                shizukuBound = false
                fileSystem = null
                setConnectionFailure("Shizuku failų tarnybos paleisti nepavyko", error)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuBound = false
            shizukuBinding = false
            bindingAttempt += 1
            if (_state.value.activeBackend in setOf(AdvancedAccessBackend.SHIZUKU_SHELL, AdvancedAccessBackend.SHIZUKU_ROOT)) {
                fileSystem = null
                _state.update {
                    it.copy(
                        activeBackend = AdvancedAccessBackend.NONE,
                        serviceUid = null,
                        connecting = false,
                        androidDataAccessible = null,
                        error = "Shizuku failų tarnyba atsijungė",
                    )
                }
            }
        }
    }

    private val rootConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootBinding = false
            bindingAttempt += 1
            runCatching {
                fileSystem = FileSystemManager.getRemote(requireNotNull(service))
                rootBound = true
                _state.update {
                    it.copy(
                        rootPermission = CapabilityState.GRANTED,
                        activeBackend = AdvancedAccessBackend.ROOT,
                        serviceUid = 0,
                        connecting = false,
                        error = null,
                    )
                }
            }.onFailure { error ->
                rootBound = false
                fileSystem = null
                setConnectionFailure("Root failų tarnybos paleisti nepavyko", error)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rootBound = false
            rootBinding = false
            bindingAttempt += 1
            if (_state.value.activeBackend == AdvancedAccessBackend.ROOT) {
                fileSystem = null
                _state.update {
                    it.copy(
                        activeBackend = AdvancedAccessBackend.NONE,
                        serviceUid = null,
                        connecting = false,
                        androidDataAccessible = null,
                        error = "Root failų tarnyba atsijungė",
                    )
                }
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshCapabilities()
        connectAlreadyGrantedBackend()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shizukuBound = false
        shizukuBinding = false
        bindingAttempt += 1
        if (_state.value.activeBackend in setOf(AdvancedAccessBackend.SHIZUKU_SHELL, AdvancedAccessBackend.SHIZUKU_ROOT)) {
            fileSystem = null
        }
        refreshCapabilities()
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        refreshCapabilities()
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            _state.update { it.copy(connecting = false, error = null) }
            bindShizuku()
        }
        else _state.update { it.copy(connecting = false, error = "Shizuku leidimas nesuteiktas") }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshCapabilities()
        connectAlreadyGrantedBackend()
    }

    fun setMode(mode: AdvancedAccessMode) {
        preferences.edit().putString(MODE_KEY, mode.name).apply()
        disconnectActiveBackend()
        _state.update {
            it.copy(
                selectedMode = mode,
                activeBackend = AdvancedAccessBackend.NONE,
                serviceUid = null,
                connecting = false,
                androidDataAccessible = null,
                error = null,
            )
        }
        refreshCapabilities()
        connectAlreadyGrantedBackend()
    }

    fun requestShizukuAccess() {
        if (_state.value.selectedMode == AdvancedAccessMode.OFF) setMode(AdvancedAccessMode.SHIZUKU)
        refreshCapabilities()
        if (!_state.value.shizukuRunning) {
            val message = if (_state.value.shizukuManagerDetected) {
                "Atidarykite Shizuku ir paleiskite tarnybą"
            } else {
                "Shizuku tarnyba nepaleista. Įdiekite arba paleiskite Shizuku"
            }
            _state.update { it.copy(connecting = false, error = message) }
            return
        }
        if (_state.value.shizukuPermission == CapabilityState.GRANTED) {
            bindShizuku()
            return
        }
        runCatching {
            _state.update { it.copy(connecting = true, error = null) }
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST)
        }.onFailure { error -> setConnectionFailure("Shizuku leidimo paprašyti nepavyko", error) }
    }

    fun requestRootAccess() {
        if (_state.value.selectedMode == AdvancedAccessMode.OFF) setMode(AdvancedAccessMode.ROOT)
        else refreshCapabilities()
        if (!AdvancedAccessCapabilityRules.canRequestRoot(_state.value.rootPermission)) {
            _state.update { it.copy(connecting = false, error = "Root prieiga šiame įrenginyje nepasiekiama") }
            return
        }
        bindRoot(requestPermission = true)
    }

    fun refreshCapabilities() {
        val installed = runCatching {
            appContext.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        }.isSuccess
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val shizukuPermission = AdvancedAccessCapabilityRules.shizukuPermission(
            binderRunning = running,
            permissionGranted = running && runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false),
            permissionDenied = running && runCatching {
                Shizuku.shouldShowRequestPermissionRationale()
            }.getOrDefault(false),
        )
        val probeRoot = _state.value.selectedMode in setOf(AdvancedAccessMode.AUTO, AdvancedAccessMode.ROOT) ||
            _state.value.activeBackend == AdvancedAccessBackend.ROOT
        val rootPermission = if (!probeRoot) {
            _state.value.rootPermission.takeIf { it == CapabilityState.GRANTED } ?: CapabilityState.AVAILABLE
        } else {
            when (runCatching { Shell.isAppGrantedRoot() }.getOrNull()) {
                true -> CapabilityState.GRANTED
                false -> CapabilityState.UNAVAILABLE
                null -> CapabilityState.AVAILABLE
            }
        }
        _state.update {
            it.copy(
                shizukuManagerDetected = installed,
                shizukuRunning = running,
                shizukuPermission = shizukuPermission,
                rootPermission = rootPermission,
                activeBackend = if (fileSystem == null) AdvancedAccessBackend.NONE else it.activeBackend,
                serviceUid = if (fileSystem == null) null else it.serviceUid,
                connecting = if (!running && it.activeBackend in setOf(AdvancedAccessBackend.SHIZUKU_SHELL, AdvancedAccessBackend.SHIZUKU_ROOT)) false else it.connecting,
            )
        }
    }

    fun fileSystemOrThrow(): FileSystemManager = fileSystem
        ?: throw IllegalStateException("Privilegijuota failų prieiga neaktyvi")

    fun reportAndroidDataProbe(accessible: Boolean, error: Throwable? = null) {
        _state.update {
            it.copy(
                androidDataAccessible = accessible,
                error = if (accessible) null else error?.message ?: "Android/data šiuo režimu nepasiekiamas",
            )
        }
    }

    fun reportOperationFailure(error: Throwable) {
        if (error is android.os.DeadObjectException) {
            fileSystem = null
            _state.update {
                it.copy(
                    activeBackend = AdvancedAccessBackend.NONE,
                    serviceUid = null,
                    connecting = false,
                    androidDataAccessible = null,
                    error = "Privilegijuota tarnyba atsijungė",
                )
            }
        }
    }

    private fun loadMode(): AdvancedAccessMode = runCatching {
        AdvancedAccessMode.valueOf(preferences.getString(MODE_KEY, null).orEmpty())
    }.getOrDefault(AdvancedAccessMode.OFF)

    private fun connectAlreadyGrantedBackend() {
        when (_state.value.selectedMode) {
            AdvancedAccessMode.OFF -> Unit
            AdvancedAccessMode.SHIZUKU -> if (_state.value.shizukuPermission == CapabilityState.GRANTED) bindShizuku()
            AdvancedAccessMode.ROOT -> if (_state.value.rootPermission == CapabilityState.GRANTED) bindRoot(requestPermission = false)
            AdvancedAccessMode.AUTO -> when {
                _state.value.shizukuPermission == CapabilityState.GRANTED -> bindShizuku()
                _state.value.rootPermission == CapabilityState.GRANTED -> bindRoot(requestPermission = false)
            }
        }
    }

    private fun bindShizuku() {
        if (shizukuBound || shizukuBinding || _state.value.connecting) return
        if (_state.value.shizukuPermission != CapabilityState.GRANTED) return
        disconnectRoot()
        runCatching {
            shizukuBinding = true
            _state.update { it.copy(connecting = true, error = null) }
            Shizuku.bindUserService(shizukuArgs, shizukuConnection)
            scheduleConnectionTimeout(shizuku = true)
        }.onFailure { error ->
            shizukuBinding = false
            setConnectionFailure("Shizuku failų tarnybos paleisti nepavyko", error)
        }
    }

    private fun bindRoot(requestPermission: Boolean) {
        if (rootBound || rootBinding || _state.value.connecting) return
        if (!requestPermission && _state.value.rootPermission != CapabilityState.GRANTED) return
        if (requestPermission && !AdvancedAccessCapabilityRules.canRequestRoot(_state.value.rootPermission)) {
            _state.update { it.copy(connecting = false, error = "Root prieiga šiame įrenginyje nepasiekiama") }
            return
        }
        disconnectShizuku()
        rootBinding = true
        _state.update { it.copy(connecting = true, error = null) }
        runCatching {
            RootService.bind(
                Intent(appContext, RootFileService::class.java),
                mainExecutor,
                rootConnection,
            )
            scheduleConnectionTimeout(shizuku = false)
        }.onFailure { error ->
            rootBinding = false
            setRootConnectionFailure("Root leidimo paprašyti nepavyko", error)
        }
    }

    private fun disconnectActiveBackend() {
        bindingAttempt += 1
        disconnectShizuku()
        disconnectRoot()
        fileSystem = null
    }

    private fun disconnectShizuku() {
        if (!shizukuBound && !shizukuBinding) return
        runCatching {
            if (Shizuku.pingBinder()) Shizuku.unbindUserService(shizukuArgs, shizukuConnection, true)
        }
        shizukuBound = false
        shizukuBinding = false
        if (_state.value.activeBackend in setOf(AdvancedAccessBackend.SHIZUKU_SHELL, AdvancedAccessBackend.SHIZUKU_ROOT)) {
            fileSystem = null
        }
    }

    private fun disconnectRoot() {
        if (!rootBound && !rootBinding) return
        runCatching { RootService.unbind(rootConnection) }
        rootBound = false
        rootBinding = false
        if (_state.value.activeBackend == AdvancedAccessBackend.ROOT) fileSystem = null
    }

    private fun setConnectionFailure(prefix: String, error: Throwable) {
        fileSystem = null
        _state.update {
            it.copy(
                activeBackend = AdvancedAccessBackend.NONE,
                serviceUid = null,
                connecting = false,
                androidDataAccessible = null,
                error = "$prefix: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun setRootConnectionFailure(prefix: String, error: Throwable) {
        fileSystem = null
        val rootPermission = when (runCatching { Shell.isAppGrantedRoot() }.getOrNull()) {
            true -> CapabilityState.GRANTED
            false -> CapabilityState.UNAVAILABLE
            null -> CapabilityState.DENIED
        }
        _state.update {
            it.copy(
                rootPermission = rootPermission,
                activeBackend = AdvancedAccessBackend.NONE,
                serviceUid = null,
                connecting = false,
                androidDataAccessible = null,
                error = "$prefix: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun scheduleConnectionTimeout(shizuku: Boolean) {
        val attempt = ++bindingAttempt
        scope.launch {
            delay(CONNECTION_TIMEOUT_MILLIS)
            if (attempt != bindingAttempt || !_state.value.connecting) return@launch
            if (shizuku) {
                disconnectShizuku()
                setConnectionFailure(
                    "Shizuku failų tarnyba neatsakė laiku",
                    IllegalStateException("15 s ryšio laukimo riba"),
                )
            } else {
                disconnectRoot()
                setRootConnectionFailure(
                    "Root failų tarnyba neatsakė laiku",
                    IllegalStateException("15 s ryšio laukimo riba"),
                )
            }
        }
    }
}
