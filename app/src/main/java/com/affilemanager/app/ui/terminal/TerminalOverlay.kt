package com.affilemanager.app.ui.terminal

import android.content.ClipboardManager
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.affilemanager.app.ui.TerminalLocation
import com.affilemanager.app.ui.TerminalUiState
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.terminal.TerminalPasteRules
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.SelectionMode
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.VTermKey

@Composable
fun TerminalOverlay(
    state: TerminalUiState,
    onRequestClose: () -> Unit,
    onConfirmClose: () -> Unit,
    onDismissCloseConfirmation: () -> Unit,
    onPaste: (String) -> Unit,
    onMultilinePaste: (String) -> Unit,
    onResolveMultilinePaste: (Boolean) -> Unit,
    onDismissMultilinePaste: () -> Unit,
    onCopyLastOutput: () -> Unit,
    onKey: (Int) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
) {
    if (!state.visible) return
    var showSoftKeyboard by remember(state.emulator) { mutableStateOf(true) }
    var selectionController by remember(state.emulator) { mutableStateOf<SelectionController?>(null) }
    val context = LocalContext.current

    fun pasteClipboard() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val item = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        item?.coerceToText(context)?.toString()?.let { text ->
            if (TerminalPasteRules.hasLineBreak(text)) {
                onMultilinePaste(text)
            } else {
                onPaste(text)
            }
        }
    }

    BackHandler {
        if (state.confirmClose) onDismissCloseConfirmation() else onRequestClose()
    }
    Dialog(
        onDismissRequest = onRequestClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("terminal-screen"),
            color = Color.Black,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(vertical = 7.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LText(
                                if (state.location == TerminalLocation.PHONE) "Telefonas" else "Serveris",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (state.location == TerminalLocation.PHONE) {
                                LText(
                                    state.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Text(
                                    state.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            state.path,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { selectionController?.copySelection() }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = uiText("Kopijuoti pažymėtą tekstą"))
                    }
                    IconButton(
                        onClick = ::pasteClipboard,
                        enabled = state.running,
                        modifier = Modifier.testTag("terminal-paste"),
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = uiText("Įklijuoti"))
                    }
                    IconButton(onClick = { showSoftKeyboard = !showSoftKeyboard }, enabled = state.running) {
                        Icon(
                            if (showSoftKeyboard) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
                            contentDescription = uiText(if (showSoftKeyboard) "Slėpti klaviatūrą" else "Rodyti klaviatūrą"),
                        )
                    }
                    IconButton(onClick = onRequestClose) {
                        Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti terminalą"))
                    }
                }
                HorizontalDivider(color = Color.DarkGray)

                Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    val emulator = state.emulator
                    when {
                        state.starting -> {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator()
                                LText(if (state.location == TerminalLocation.PHONE) "Paleidžiamas telefono terminalas…" else "Jungiamasi prie serverio terminalo…")
                            }
                        }
                        emulator == null -> {
                            val failure = state.failure
                            Column(
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                LText(
                                    failure?.title ?: "Terminalo atidaryti nepavyko",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                failure?.let {
                                    LText(it.detail, color = Color.White)
                                    LText("Ką daryti: ${it.suggestion}", color = Color.White)
                                    LText(
                                        "Diagnostikos kodas: ${it.diagnosticCode}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.LightGray,
                                    )
                                }
                                Button(onClick = onRequestClose) { LText("Uždaryti") }
                            }
                        }
                        else -> {
                            Terminal(
                                terminalEmulator = emulator,
                                modifier = Modifier.fillMaxSize().testTag("terminal-canvas"),
                                typeface = Typeface.MONOSPACE,
                                initialFontSize = 12.sp,
                                backgroundColor = Color.Black,
                                foregroundColor = Color.White,
                                keyboardEnabled = state.running,
                                showSoftKeyboard = showSoftKeyboard && state.running,
                                modifierManager = state.modifiers,
                                onSelectionControllerAvailable = { selectionController = it },
                                onPasteRequest = ::pasteClipboard,
                            )
                            if (!state.running) {
                                Surface(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = MaterialTheme.shapes.medium,
                                ) {
                                    LText(
                                        state.endedMessage ?: "Terminalo seansas baigtas",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                }

                TerminalKeyBar(
                    state = state,
                    selectionController = selectionController,
                    onCopyLastOutput = onCopyLastOutput,
                    onKey = onKey,
                    onToggleCtrl = onToggleCtrl,
                    onToggleAlt = onToggleAlt,
                )
            }
        }
    }

    if (state.confirmClose) {
        AlertDialog(
            onDismissRequest = onDismissCloseConfirmation,
            title = { LText("Uždaryti terminalą?") },
            text = { LText("Veikianti komanda bus sustabdyta ir terminalo seansas bus uždarytas.") },
            confirmButton = { Button(onClick = onConfirmClose) { LText("Uždaryti") } },
            dismissButton = { TextButton(onClick = onDismissCloseConfirmation) { LText("Atšaukti") } },
        )
    }

    state.pendingMultilinePaste?.let {
        AlertDialog(
            onDismissRequest = onDismissMultilinePaste,
            title = { LText("Įklijuoti kelių eilučių tekstą?") },
            text = {
                LText(
                    "Įklijuojant įprastai eilučių lūžiai veiks kaip Enter. Galite juos pakeisti tarpais ir įklijuoti kaip 1 eilutę.",
                )
            },
            confirmButton = {
                Button(
                    onClick = { onResolveMultilinePaste(true) },
                    modifier = Modifier.testTag("terminal-paste-single-line"),
                ) {
                    LText("Įklijuoti kaip 1 eilutę")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onResolveMultilinePaste(false) },
                    modifier = Modifier.testTag("terminal-paste-lines"),
                ) {
                    LText("Įklijuoti")
                }
            },
        )
    }
}

@Composable
private fun TerminalKeyBar(
    state: TerminalUiState,
    selectionController: SelectionController?,
    onCopyLastOutput: () -> Unit,
    onKey: (Int) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("terminal-key-bar"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalKey("Žymėti tekstą", state.emulator != null, Modifier.testTag("terminal-select-text")) {
            selectionController?.startSelection(SelectionMode.WORD)
        }
        TerminalKey("Kopijuoti pažymėtą", state.emulator != null, Modifier.testTag("terminal-copy-selection")) {
            selectionController?.copySelection()
        }
        TerminalKey("Kopijuoti paskutinę išvestį", state.emulator != null, Modifier.testTag("terminal-copy-last")) {
            onCopyLastOutput()
        }
        TerminalKey("Esc", state.running) { onKey(VTermKey.ESCAPE) }
        FilterChip(
            selected = state.modifiers?.ctrlActive == true,
            onClick = onToggleCtrl,
            enabled = state.running,
            label = { Text("Ctrl") },
        )
        FilterChip(
            selected = state.modifiers?.altActive == true,
            onClick = onToggleAlt,
            enabled = state.running,
            label = { Text("Alt") },
        )
        TerminalKey("Tab", state.running) { onKey(VTermKey.TAB) }
        TerminalKey("←", state.running) { onKey(VTermKey.LEFT) }
        TerminalKey("↑", state.running) { onKey(VTermKey.UP) }
        TerminalKey("↓", state.running) { onKey(VTermKey.DOWN) }
        TerminalKey("→", state.running) { onKey(VTermKey.RIGHT) }
        TerminalKey("Home", state.running) { onKey(VTermKey.HOME) }
        TerminalKey("End", state.running) { onKey(VTermKey.END) }
        TerminalKey("PgUp", state.running) { onKey(VTermKey.PAGEUP) }
        TerminalKey("PgDn", state.running) { onKey(VTermKey.PAGEDOWN) }
        TerminalKey("Enter", state.running) { onKey(VTermKey.ENTER) }
    }
}

@Composable
private fun TerminalKey(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { LText(label) }
}
