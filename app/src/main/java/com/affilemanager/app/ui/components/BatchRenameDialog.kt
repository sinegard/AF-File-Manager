package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.operations.BatchRenamePreviewItem
import com.affilemanager.app.operations.RenameCaseMode
import com.affilemanager.app.ui.MainViewModel

@Composable
fun BatchRenameDialog(viewModel: MainViewModel) {
    val state by viewModel.batchRename.collectAsStateWithLifecycle()
    if (!state.open) return
    val spec = state.spec
    var numberStartText by remember(state.sourcePaths) { mutableStateOf(spec.numberStart.toString()) }
    var numberPaddingText by remember(state.sourcePaths) { mutableStateOf(spec.numberPadding.toString()) }
    val numberInputsValid = !spec.numberingEnabled || (
        numberStartText.toIntOrNull()?.let { it in 0..999_999_999 } == true &&
            numberPaddingText.toIntOrNull()?.let { it in 1..9 } == true
    )

    Dialog(
        onDismissRequest = viewModel::closeBatchRename,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f).testTag("batch_rename_dialog"),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Masinis pervadinimas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Pakeitimai bus vykdomi tik patvirtinus planą", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = viewModel::closeBatchRename) { Text("Uždaryti") }
                }
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("batch_rename_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            OutlinedTextField(
                                value = spec.findText,
                                onValueChange = { viewModel.updateBatchRenameSpec(spec.copy(findText = it)) },
                                modifier = Modifier.fillMaxWidth().testTag("batch_rename_find"),
                                label = { Text("Rasti pavadinime") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = spec.replacementText,
                                onValueChange = { viewModel.updateBatchRenameSpec(spec.copy(replacementText = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Pakeisti į") },
                                singleLine = true,
                            )
                            FilterChip(
                                selected = spec.useRegex,
                                onClick = { viewModel.updateBatchRenameSpec(spec.copy(useRegex = !spec.useRegex)) },
                                label = { Text("Reguliarioji išraiška") },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = spec.prefix,
                                    onValueChange = { viewModel.updateBatchRenameSpec(spec.copy(prefix = it)) },
                                    modifier = Modifier.weight(1f).testTag("batch_rename_prefix"),
                                    label = { Text("Prefiksas") },
                                    singleLine = true,
                                )
                                OutlinedTextField(
                                    value = spec.suffix,
                                    onValueChange = { viewModel.updateBatchRenameSpec(spec.copy(suffix = it)) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Sufiksas") },
                                    singleLine = true,
                                )
                            }
                            Text("Raidės", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                RenameCaseMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = spec.caseMode == mode,
                                        onClick = { viewModel.updateBatchRenameSpec(spec.copy(caseMode = mode)) },
                                        label = { Text(caseModeLabel(mode)) },
                                    )
                                }
                            }
                            FilterChip(
                                selected = spec.numberingEnabled,
                                onClick = { viewModel.updateBatchRenameSpec(spec.copy(numberingEnabled = !spec.numberingEnabled)) },
                                label = { Text("Pridėti numeravimą") },
                            )
                            if (spec.numberingEnabled) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = numberStartText,
                                        onValueChange = { raw ->
                                            numberStartText = raw.filter(Char::isDigit).take(9)
                                            numberStartText.toIntOrNull()?.let { value ->
                                                viewModel.updateBatchRenameSpec(spec.copy(numberStart = value))
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("Pradžia") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = numberStartText.toIntOrNull()?.let { it !in 0..999_999_999 } != false,
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = numberPaddingText,
                                        onValueChange = { raw ->
                                            numberPaddingText = raw.filter(Char::isDigit).take(1)
                                            numberPaddingText.toIntOrNull()?.takeIf { it in 1..9 }?.let { value ->
                                                viewModel.updateBatchRenameSpec(spec.copy(numberPadding = value))
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("Skaitmenys") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        isError = numberPaddingText.toIntOrNull()?.let { it !in 1..9 } != false,
                                        singleLine = true,
                                    )
                                    OutlinedTextField(
                                        value = spec.numberSeparator,
                                        onValueChange = { viewModel.updateBatchRenameSpec(spec.copy(numberSeparator = it.take(8))) },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("Skirtukas") },
                                        singleLine = true,
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = spec.extensionOverride,
                                onValueChange = { viewModel.updateBatchRenameSpec(spec.copy(extensionOverride = it)) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Naujas failų plėtinys (nebūtinas)") },
                                supportingText = { Text("Tuščia reikšmė palieka esamą plėtinį; aplankams netaikoma") },
                                singleLine = true,
                            )
                        }
                    }

                    item {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Peržiūra", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (state.running) CircularProgressIndicator(modifier = Modifier.padding(3.dp))
                            state.preview?.let { Text("Keisis ${it.changedCount} / ${it.items.size}") }
                        }
                    }

                    state.error?.let { error ->
                        item { ErrorText(error) }
                    }
                    if (!numberInputsValid) {
                        item { ErrorText("Patikrinkite numeravimo pradžią ir skaitmenų skaičių") }
                    }
                    state.preview?.errors?.forEach { error ->
                        item(key = "global:$error") { ErrorText(error) }
                    }
                    state.preview?.let { preview ->
                        items(preview.items, key = BatchRenamePreviewItem::originalPath) { item ->
                            RenamePreviewRow(item)
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = viewModel::closeBatchRename) { Text("Atšaukti") }
                    Button(
                        onClick = viewModel::executeBatchRename,
                        enabled = numberInputsValid && !state.running && state.preview?.canExecute == true,
                        modifier = Modifier.testTag("batch_rename_execute"),
                    ) {
                        Text("Pervadinti ${state.preview?.changedCount ?: 0}")
                    }
                }
            }
        }
    }
}

@Composable
private fun RenamePreviewRow(item: BatchRenamePreviewItem) {
    val issue = item.issue
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(item.originalName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (item.changed) "→ ${item.targetName}" else "→ Nesikeičia",
            color = if (issue != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (issue != null) Text(issue, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun caseModeLabel(mode: RenameCaseMode): String = when (mode) {
    RenameCaseMode.KEEP -> "Nekeisti"
    RenameCaseMode.LOWERCASE -> "mažosios"
    RenameCaseMode.UPPERCASE -> "DIDŽIOSIOS"
}
