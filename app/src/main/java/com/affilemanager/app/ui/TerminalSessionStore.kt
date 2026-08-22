package com.affilemanager.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import com.affilemanager.app.terminal.TerminalBackend
import com.affilemanager.app.terminal.TerminalKeepAliveService
import com.affilemanager.app.terminal.TerminalLimits
import com.affilemanager.app.terminal.TerminalModifierState
import com.affilemanager.app.terminal.TerminalPasteResult
import com.affilemanager.app.terminal.TerminalSessionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.terminal.TerminalEmulator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class TerminalLocation {
    PHONE,
    SERVER,
}

data class TerminalFailureUi(
    val title: String,
    val detail: String,
    val suggestion: String,
    val diagnosticCode: String,
)

data class TerminalUiState(
    val visible: Boolean = false,
    val starting: Boolean = false,
    val running: Boolean = false,
    val location: TerminalLocation = TerminalLocation.PHONE,
    val title: String = "",
    val path: String = "",
    val emulator: TerminalEmulator? = null,
    val modifiers: TerminalModifierState? = null,
    val failure: TerminalFailureUi? = null,
    val endedMessage: String? = null,
    val confirmClose: Boolean = false,
)

/**
 * Owns the live terminal independently from an Activity or ViewModel. Android
 * may recreate either while AF File Manager is in the background; the PTY/SSH
 * transport must remain attached until the user explicitly closes it.
 */
class TerminalSessionStore(
    private val application: Application,
    parentScope: CoroutineScope,
) {
    private val lock = Any()
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )
    private val requestId = AtomicLong(0L)

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    @Volatile
    private var session: TerminalSessionController? = null

    @Volatile
    private var openJob: Job? = null

    fun begin(
        location: TerminalLocation,
        title: String,
        path: String,
        openBackend: suspend () -> TerminalBackend,
        errorInfo: (Throwable) -> TerminalFailureUi,
    ) {
        val currentRequest = synchronized(lock) {
            if (_state.value.visible) return
            requestId.incrementAndGet().also {
                _state.value = TerminalUiState(
                    visible = true,
                    starting = true,
                    location = location,
                    title = title,
                    path = path,
                )
            }
        }

        runCatching { TerminalKeepAliveService.start(application, location) }
            .onFailure {
                _notices.tryEmit("Terminalo sesija gali būti sustabdyta programą sumažinus")
            }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var backend: TerminalBackend? = null
            var controller: TerminalSessionController? = null
            val transportEnded = AtomicBoolean(false)
            try {
                backend = openBackend()
                if (currentRequest != requestId.get()) return@launch
                controller = TerminalSessionController.create(
                    backend = requireNotNull(backend),
                    parentScope = scope,
                    onClipboardCopy = ::copyTerminalText,
                    onTransportEnded = { transportError ->
                        transportEnded.set(true)
                        handleTransportEnded(currentRequest, transportError)
                    },
                )
                backend = null

                var closeController = false
                synchronized(lock) {
                    if (currentRequest != requestId.get() || transportEnded.get()) {
                        closeController = true
                    } else {
                        session = controller
                        _state.update {
                            it.copy(
                                starting = false,
                                running = true,
                                emulator = controller.emulator,
                                modifiers = controller.modifiers,
                                failure = null,
                                endedMessage = null,
                            )
                        }
                    }
                }
                if (closeController) controller.close()
            } catch (cancelled: CancellationException) {
                controller?.close()
                withContext(NonCancellable + Dispatchers.IO) { backend?.close() }
                throw cancelled
            } catch (error: Throwable) {
                controller?.close()
                withContext(Dispatchers.IO) { backend?.close() }
                if (currentRequest == requestId.get()) {
                    _state.update {
                        it.copy(starting = false, running = false, failure = errorInfo(error))
                    }
                    TerminalKeepAliveService.stop(application)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { backend?.close() }
                synchronized(lock) {
                    if (currentRequest == requestId.get()) openJob = null
                }
            }
        }
        synchronized(lock) {
            if (currentRequest == requestId.get()) openJob = job else job.cancel()
        }
        job.start()
    }

    fun requestClose() {
        val snapshot = _state.value
        if (!snapshot.visible) return
        if (snapshot.running || snapshot.starting) {
            _state.update { it.copy(confirmClose = true) }
        } else {
            closeNow()
        }
    }

    fun dismissCloseConfirmation() {
        _state.update { it.copy(confirmClose = false) }
    }

    fun confirmClose() {
        closeNow()
    }

    fun paste(text: String): TerminalPasteResult = session?.paste(text) ?: TerminalPasteResult.BUSY

    fun dispatchKey(key: Int) {
        session?.dispatchKey(key)
    }

    fun toggleCtrl() {
        session?.modifiers?.toggleCtrl()
    }

    fun toggleAlt() {
        session?.modifiers?.toggleAlt()
    }

    fun closeNow() {
        val sessionToClose: TerminalSessionController?
        val jobToCancel: Job?
        synchronized(lock) {
            requestId.incrementAndGet()
            sessionToClose = session
            jobToCancel = openJob
            session = null
            openJob = null
            _state.value = TerminalUiState()
        }
        jobToCancel?.cancel()
        if (sessionToClose != null) scope.launch { sessionToClose.close() }
        TerminalKeepAliveService.stop(application)
    }

    private fun handleTransportEnded(currentRequest: Long, transportError: String?) {
        val shouldStop = synchronized(lock) {
            if (currentRequest != requestId.get()) {
                false
            } else {
                session = null
                _state.update { state ->
                    state.copy(
                        starting = false,
                        running = false,
                        endedMessage = transportError,
                    )
                }
                true
            }
        }
        if (shouldStop) TerminalKeepAliveService.stop(application)
    }

    private fun copyTerminalText(text: String) {
        if (text.isEmpty()) return
        if (text.toByteArray(Charsets.UTF_8).size > TerminalLimits.MAX_CLIPBOARD_COPY_BYTES) {
            _notices.tryEmit("Pažymėtas terminalo tekstas viršija 64 KiB kopijavimo ribą")
            return
        }
        val clipboard = application.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("AF File Manager terminal", text))
    }
}
