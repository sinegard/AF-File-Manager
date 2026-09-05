package com.affilemanager.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.theme.CustomThemeColors
import com.affilemanager.app.ui.theme.CustomThemeRules

@Composable
internal fun CustomPaletteDialog(
    initial: CustomThemeColors,
    onSave: (CustomThemeColors) -> Boolean,
    onDismiss: () -> Unit,
) {
    var fields by remember { mutableStateOf(initial.values().map(CustomThemeRules::hex)) }
    var saveFailed by remember { mutableStateOf(false) }
    val parsed = remember(fields) { CustomThemeRules.parseDraft(fields) }
    val labels = listOf("Pagrindinė spalva", "Antrinė spalva", "Trečioji spalva", "Fono spalva", "Kortelių spalva")
    AfModalDialog(
        title = "Pasirinktinė paletė",
        icon = Icons.Rounded.Palette,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("custom_palette_dialog"),
        actions = {
            TextButton(onClick = { fields = CustomThemeColors().values().map(CustomThemeRules::hex); saveFailed = false }) { LText("Atstatyti") }
            TextButton(onClick = onDismiss) { LText("Atšaukti") }
            TextButton(
                onClick = { parsed?.let { if (onSave(it)) onDismiss() else saveFailed = true } },
                enabled = parsed != null,
                modifier = Modifier.testTag("custom_palette_save"),
            ) { LText("Išsaugoti") }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LText("Spalvas įrašykite #RRGGBB formatu. Teksto kontrastas pritaikomas automatiškai.", style = MaterialTheme.typography.bodySmall)
            fields.forEachIndexed { index, value ->
                val color = CustomThemeRules.parseHex(value)
                OutlinedTextField(
                    value = value,
                    onValueChange = { updated ->
                        if (updated.length <= 9) { fields = fields.toMutableList().also { it[index] = updated }; saveFailed = false }
                    },
                    label = { LText(labels[index]) },
                    singleLine = true,
                    isError = color == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    leadingIcon = { Box(Modifier.size(24.dp).background(color?.let(::Color) ?: Color.Transparent, CircleShape)) },
                    modifier = Modifier.fillMaxWidth().testTag("custom_color_$index"),
                )
            }
            if (saveFailed) LText("Išvaizdos nustatymo išsaugoti nepavyko", color = MaterialTheme.colorScheme.error)
        }
    }
}
