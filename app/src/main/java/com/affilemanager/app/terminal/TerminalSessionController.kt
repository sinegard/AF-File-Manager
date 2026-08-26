package com.affilemanager.app.terminal

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * termlib already posts each native keyboard-output chunk to this Handler.
 * The paste check must stay behind those queued chunks so one IME commit is
 * observed as one batch rather than leaking its Enter keys one by one.
 */
internal fun Handler.postAfterQueuedTerminalKeyboardInput(action: () -> Unit) {
    post(action)
}

class TerminalModifierState : ModifierManager {
    var ctrlActive by mutableStateOf(false)
        private set
    var altActive by mutableStateOf(false)
        private set

    override fun isCtrlActive(): Boolean = ctrlActive
    override fun isAltActive(): Boolean = altActive
    override fun isShiftActive(): Boolean = false

    fun toggleCtrl() {
        ctrlActive = !ctrlActive
    }

    fun toggleAlt() {
        altActive = !altActive
    }

    fun mask(): Int = (if (altActive) 2 else 0) or (if (ctrlActive) 4 else 0)

    override fun clearTransients() {
        ctrlActive = false
        altActive = false
    }
}

class TerminalSessionController private constructor(
    val emulator: TerminalEmulator,
    val modifiers: TerminalModifierState,
    private val backend: TerminalBackend,
    parentScope: CoroutineScope,
    private val onTransportEnded: (String?) -> Unit,
    clipboardText: () -> String?,
    onSystemMultilinePaste: (String) -> Unit,
    onSystemPasteTooLarge: () -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val endedDelivered = AtomicBoolean(false)
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val pendingInput = Channel<ByteArray>(TerminalLimits.MAX_PENDING_INPUT_CHUNKS)
    private val outputCapture = TerminalOutputCapture()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val keyboardPasteGuard = TerminalKeyboardPasteGuard(
        clipboardText = clipboardText,
        onMultilinePaste = onSystemMultilinePaste,
        onTooLarge = onSystemPasteTooLarge,
        send = { data -> enqueue(data) },
        scheduleFlush = mainHandler::postAfterQueuedTerminalKeyboardInput,
    )

    companion object {
        fun create(
            backend: TerminalBackend,
            parentScope: CoroutineScope,
            onClipboardCopy: (String) -> Unit,
            onTransportEnded: (String?) -> Unit,
            clipboardText: () -> String?,
            onSystemMultilinePaste: (String) -> Unit,
            onSystemPasteTooLarge: () -> Unit,
        ): TerminalSessionController {
            val controllerRef = arrayOfNulls<TerminalSessionController>(1)
            val modifiers = TerminalModifierState()
            val emulator = TerminalEmulatorFactory.create(
                looper = Looper.getMainLooper(),
                initialRows = TerminalLimits.INITIAL_ROWS,
                initialCols = TerminalLimits.INITIAL_COLUMNS,
                defaultForeground = Color.White,
                defaultBackground = Color.Black,
                onKeyboardInput = { data -> controllerRef[0]?.enqueueKeyboardInput(data) },
                onResize = { dimensions -> controllerRef[0]?.resize(dimensions) },
                onClipboardCopy = onClipboardCopy,
                autoDetectUrls = true,
            )
            val controller = TerminalSessionController(
                emulator = emulator,
                modifiers = modifiers,
                backend = backend,
                parentScope = parentScope,
                onTransportEnded = onTransportEnded,
                clipboardText = clipboardText,
                onSystemMultilinePaste = onSystemMultilinePaste,
                onSystemPasteTooLarge = onSystemPasteTooLarge,
            )
            controllerRef[0] = controller
            controller.startTransport()
            return controller
        }
    }

    fun enqueue(data: ByteArray): Boolean {
        if (closed.get() || data.isEmpty() || data.size > TerminalLimits.MAX_INPUT_CHUNK_BYTES) return false
        val copy = data.copyOf()
        val accepted = pendingInput.trySend(copy).isSuccess
        if (accepted) outputCapture.recordAcceptedInput(copy) else copy.fill(0)
        return accepted
    }

    private fun enqueueKeyboardInput(data: ByteArray) {
        keyboardPasteGuard.accept(data)
    }

    internal fun lastCommandOutput(): CapturedTerminalOutput? {
        val semanticOutput = emulator.getLastCommandOutput()?.takeIf { it.isNotBlank() }
        return semanticOutput?.let { CapturedTerminalOutput(it.trimEnd(), truncated = false) }
            ?: outputCapture.snapshot()
    }

    fun paste(text: String): TerminalPasteResult {
        val bytes = TerminalPasteRules.encode(text) ?: return TerminalPasteResult.TOO_LARGE
        val accepted = enqueue(bytes)
        bytes.fill(0)
        return if (accepted) TerminalPasteResult.ACCEPTED else TerminalPasteResult.BUSY
    }

    fun dispatchKey(key: Int) {
        if (closed.get()) return
        emulator.dispatchKey(modifiers.mask(), key)
        modifiers.clearTransients()
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        keyboardPasteGuard.cancel()
        pendingInput.close()
        while (true) pendingInput.tryReceive().getOrNull()?.fill(0) ?: break
        withContext(Dispatchers.IO) { backend.close() }
        sessionJob.cancel()
    }

    private fun startTransport() {
        scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(TerminalLimits.IO_CHUNK_BYTES)
            try {
                while (isActive && !closed.get()) {
                    val count = backend.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        outputCapture.recordOutput(buffer, 0, count)
                        emulator.writeInput(buffer, 0, count)
                    }
                }
                if (!closed.get()) deliverEnded(null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!closed.get()) deliverEnded("Terminalo ryšys netikėtai nutrūko")
            } finally {
                buffer.fill(0)
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                for (data in pendingInput) {
                    try {
                        TerminalPasteRules.writeInChunks(data.size) { offset, length ->
                            backend.write(data, offset, length)
                        }
                    } finally {
                        data.fill(0)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!closed.get()) deliverEnded("Terminalo įvesties ryšys netikėtai nutrūko")
            }
        }
    }

    private fun resize(dimensions: TerminalDimensions) {
        if (closed.get()) return
        val rows = dimensions.rows.coerceIn(TerminalLimits.MIN_DIMENSION, TerminalLimits.MAX_DIMENSION)
        val columns = dimensions.columns.coerceIn(TerminalLimits.MIN_DIMENSION, TerminalLimits.MAX_DIMENSION)
        scope.launch(Dispatchers.IO) {
            runCatching { backend.resize(rows, columns) }
                .onFailure { if (!closed.get()) deliverEnded("Terminalo dydžio pakeisti nepavyko") }
        }
    }

    private fun deliverEnded(message: String?) {
        if (!endedDelivered.compareAndSet(false, true)) return
        keyboardPasteGuard.cancel()
        onTransportEnded(message)
        scope.launch(Dispatchers.IO) {
            if (closed.compareAndSet(false, true)) {
                pendingInput.close()
                runCatching { backend.close() }
            }
            sessionJob.cancel()
        }
    }
}
