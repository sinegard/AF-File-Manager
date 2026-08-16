package com.affilemanager.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.affilemanager.app.editing.LineEnding
import com.affilemanager.app.editing.TextEncoding
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

@Composable
fun FullTextEditor(
    sourceKey: String,
    fileName: String,
    text: String,
    readOnly: Boolean,
    encoding: TextEncoding,
    lineEnding: LineEnding,
    onTextChanged: (String) -> Unit,
    onEncodingChanged: (TextEncoding) -> Unit,
    onLineEndingChanged: (LineEnding) -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
) {
    val controller = remember(sourceKey) { CodeEditorController() }
    var canUndo by remember(sourceKey) { mutableStateOf(false) }
    var canRedo by remember(sourceKey) { mutableStateOf(false) }
    var cursor by remember(sourceKey) { mutableStateOf(EditorCursor(1, 1, 0, 1)) }
    var searchStatus by remember(sourceKey) { mutableStateOf(EditorSearchStatus(0, 0, false)) }
    var showSearch by remember(sourceKey) { mutableStateOf(false) }
    var showReplace by remember(sourceKey) { mutableStateOf(false) }
    var query by remember(sourceKey) { mutableStateOf("") }
    var replacement by remember(sourceKey) { mutableStateOf("") }
    var matchCase by remember(sourceKey) { mutableStateOf(false) }
    var wholeWord by remember(sourceKey) { mutableStateOf(false) }
    var wordWrap by remember(sourceKey) { mutableStateOf(true) }
    var fontSize by remember(sourceKey) { mutableStateOf(14f) }
    var language by remember(sourceKey, fileName) { mutableStateOf(EditorLanguage.detect(fileName)) }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var showEncodingMenu by remember { mutableStateOf(false) }
    var showLineEndingMenu by remember { mutableStateOf(false) }
    var showGoToLine by remember(sourceKey) { mutableStateOf(false) }
    var goToLineText by remember(sourceKey) { mutableStateOf("1") }
    var actionStatus by remember(sourceKey) { mutableStateOf<String?>(null) }
    val darkTheme = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme, darkTheme) {
        EditorColors(
            background = scheme.surface.toArgb(),
            foreground = scheme.onSurface.toArgb(),
            gutterBackground = scheme.surfaceVariant.toArgb(),
            gutterForeground = scheme.onSurfaceVariant.toArgb(),
            keyword = scheme.primary.toArgb(),
            string = if (darkTheme) Color(0xFF70D49A).toArgb() else Color(0xFF087A43).toArgb(),
            comment = scheme.onSurfaceVariant.toArgb(),
            number = scheme.tertiary.toArgb(),
            heading = scheme.primary.toArgb(),
            tag = scheme.error.toArgb(),
            searchMatch = Color(0x66FACC15).toArgb(),
            currentSearchMatch = Color(0x99FB923C).toArgb(),
        )
    }
    val editorContentDescription = uiText("Teksto redaktorius")

    LaunchedEffect(controller, text, readOnly, wordWrap, fontSize, language, colors, query, matchCase, wholeWord, editorContentDescription) {
        controller.setTextFromModel(text)
        controller.setReadOnly(readOnly)
        controller.setWordWrap(wordWrap)
        controller.setFontSizeSp(fontSize)
        controller.setLanguage(language)
        controller.applyColors(colors)
        controller.setSearch(query, matchCase, wholeWord)
        controller.setEditorContentDescription(editorContentDescription)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = { controller.undo() }, enabled = canUndo && !readOnly) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = uiText("Anuliuoti"))
            }
            IconButton(onClick = { controller.redo() }, enabled = canRedo && !readOnly) {
                Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = uiText("Pakartoti"))
            }
            IconButton(onClick = { showSearch = !showSearch; if (!showSearch) showReplace = false }) {
                Icon(Icons.Rounded.Search, contentDescription = uiText("Ieškoti"))
            }
            IconButton(onClick = { showSearch = true; showReplace = true }) {
                Icon(Icons.Rounded.FindReplace, contentDescription = uiText("Rasti ir pakeisti"))
            }
            IconButton(onClick = { goToLineText = cursor.line.toString(); showGoToLine = true }) {
                Icon(Icons.Rounded.FormatListNumbered, contentDescription = uiText("Eiti į eilutę"))
            }
            IconButton(onClick = { wordWrap = !wordWrap }) {
                Icon(
                    Icons.AutoMirrored.Rounded.WrapText,
                    contentDescription = uiText("Laužyti ilgas eilutes"),
                    tint = if (wordWrap) scheme.primary else scheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(10f) }) {
                Icon(Icons.Rounded.ZoomOut, contentDescription = uiText("Mažinti tekstą"))
            }
            IconButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(28f) }) {
                Icon(Icons.Rounded.ZoomIn, contentDescription = uiText("Didinti tekstą"))
            }
            Box {
                TextButton(onClick = { showLanguageMenu = true }) { LText(language.label) }
                DropdownMenu(expanded = showLanguageMenu, onDismissRequest = { showLanguageMenu = false }) {
                    EditorLanguage.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { LText(option.label) },
                            onClick = { language = option; showLanguageMenu = false },
                        )
                    }
                }
            }
        }
        HorizontalDivider()

        if (showSearch) {
            SearchReplaceBar(
                query = query,
                replacement = replacement,
                showReplace = showReplace,
                matchCase = matchCase,
                wholeWord = wholeWord,
                status = searchStatus,
                readOnly = readOnly,
                actionStatus = actionStatus,
                onQueryChanged = { query = it; actionStatus = null },
                onReplacementChanged = { replacement = it },
                onMatchCaseChanged = { matchCase = it },
                onWholeWordChanged = { wholeWord = it },
                onPrevious = { controller.findNext(false) },
                onNext = { controller.findNext(true) },
                onReplace = {
                    actionStatus = if (controller.replaceCurrent(replacement)) "Pakeistas vienas atitikmuo" else "Atitikmens nėra"
                },
                onReplaceAll = {
                    val count = controller.replaceAll(replacement)
                    actionStatus = "Pakeista: $count"
                },
                onClose = { showSearch = false; showReplace = false; query = ""; actionStatus = null },
            )
            HorizontalDivider()
        }

        key(sourceKey) {
            AndroidView(
                factory = { context ->
                    CodeEditorView(context).also(controller::attach)
                },
                update = { editor ->
                    controller.attach(editor)
                    editor.onTextChanged = onTextChanged
                    editor.onCursorChanged = { cursor = it }
                    editor.onHistoryChanged = { undo, redo -> canUndo = undo; canRedo = redo }
                    editor.onSearchChanged = { searchStatus = it }
                    editor.onShortcut = { shortcut ->
                        when (shortcut) {
                            EditorShortcut.SAVE -> onSave()
                            EditorShortcut.SAVE_AS -> onSaveAs()
                            EditorShortcut.FIND -> { showSearch = true; showReplace = false }
                            EditorShortcut.REPLACE -> { showSearch = true; showReplace = true }
                            EditorShortcut.GO_TO_LINE -> { goToLineText = cursor.line.toString(); showGoToLine = true }
                        }
                    }
                },
                onRelease = { editor ->
                    controller.detach(editor)
                    editor.dispose()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("full-text-editor")
                    .semantics {
                        requestFocus {
                            controller.requestFocus()
                            true
                        }
                        setText { replacementText ->
                            controller.replaceTextFromAccessibility(replacementText.text)
                        }
                    },
            )
        }

        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LText("Eil. ${cursor.line}, stulp. ${cursor.column}", style = MaterialTheme.typography.labelSmall)
            LText("${cursor.totalLines} eilučių", style = MaterialTheme.typography.labelSmall)
            if (cursor.selectedCharacters > 0) LText("Pažymėta simbolių: ${cursor.selectedCharacters}", style = MaterialTheme.typography.labelSmall)
            Box {
                TextButton(onClick = { showEncodingMenu = true }, contentPadding = compactButtonPadding()) {
                    Text(encoding.label, style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = showEncodingMenu, onDismissRequest = { showEncodingMenu = false }) {
                    TextEncoding.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { onEncodingChanged(option); showEncodingMenu = false },
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { showLineEndingMenu = true }, contentPadding = compactButtonPadding()) {
                    Text(lineEnding.label, style = MaterialTheme.typography.labelSmall)
                }
                DropdownMenu(expanded = showLineEndingMenu, onDismissRequest = { showLineEndingMenu = false }) {
                    LineEnding.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = { onLineEndingChanged(option); showLineEndingMenu = false },
                        )
                    }
                }
            }
            if (text.length > EditorSyntaxHighlighter.MAX_HIGHLIGHT_CHARS) {
                LText("Dideliam failui sintaksės spalvinimas išjungtas", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (showGoToLine) {
        AlertDialog(
            onDismissRequest = { showGoToLine = false },
            title = { LText("Eiti į eilutę") },
            text = {
                OutlinedTextField(
                    value = goToLineText,
                    onValueChange = { value -> goToLineText = value.filter(Char::isDigit).take(9) },
                    label = { LText("Eilutė") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        controller.goToLine(goToLineText.toIntOrNull() ?: 1)
                        showGoToLine = false
                    },
                    enabled = goToLineText.toIntOrNull() != null,
                ) { LText("Eiti") }
            },
            dismissButton = { TextButton(onClick = { showGoToLine = false }) { LText("Atšaukti") } },
        )
    }
}

@Composable
private fun SearchReplaceBar(
    query: String,
    replacement: String,
    showReplace: Boolean,
    matchCase: Boolean,
    wholeWord: Boolean,
    status: EditorSearchStatus,
    readOnly: Boolean,
    actionStatus: String?,
    onQueryChanged: (String) -> Unit,
    onReplacementChanged: (String) -> Unit,
    onMatchCaseChanged: (Boolean) -> Unit,
    onWholeWordChanged: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onReplace: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    label = { LText("Ieškoti") },
                    singleLine = true,
                )
                Text(
                    if (status.total == 0) "0" else "${status.current}/${status.total}${if (status.limited) "+" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                )
                IconButton(onClick = onPrevious, enabled = status.total > 0) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = uiText("Ankstesnis"))
                }
                IconButton(onClick = onNext, enabled = status.total > 0) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = uiText("Kitas"))
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti")) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(checked = matchCase, onCheckedChange = onMatchCaseChanged, modifier = Modifier.size(34.dp))
                LText("Skirti didžiąsias raides", style = MaterialTheme.typography.labelSmall)
                Checkbox(checked = wholeWord, onCheckedChange = onWholeWordChanged, modifier = Modifier.size(34.dp))
                LText("Visas žodis", style = MaterialTheme.typography.labelSmall)
                actionStatus?.let { LText(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            }
            if (showReplace) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = onReplacementChanged,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        label = { LText("Pakeisti į") },
                        singleLine = true,
                    )
                    OutlinedButton(onClick = onReplace, enabled = !readOnly && query.isNotEmpty()) { LText("Pakeisti") }
                    Button(onClick = onReplaceAll, enabled = !readOnly && query.isNotEmpty()) { LText("Pakeisti visus") }
                }
            }
        }
    }
}

@Composable
private fun compactButtonPadding() = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
