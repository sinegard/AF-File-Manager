package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.affilemanager.app.transfer.ManufacturerSharingActions
import com.affilemanager.app.transfer.ManufacturerSharingTarget
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.theme.AfDropdownMenu

@Composable
internal fun NearbyManufacturerSharingButton(enabled: Boolean) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var unavailable by remember { mutableStateOf<ManufacturerSharingTarget?>(null) }
    var settingsFailed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box {
            OutlinedButton(
                onClick = { expanded = true; unavailable = null; settingsFailed = false },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("nearby_manufacturer_sharing"),
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                LText("Atidaryti su kita programa", modifier = Modifier.padding(start = 7.dp))
            }
            AfDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                ManufacturerSharingTarget.entries.forEach { target ->
                    DropdownMenuItem(
                        text = { Text(target.label) },
                        onClick = {
                            expanded = false
                            if (!ManufacturerSharingActions.open(context, target)) unavailable = target
                        },
                        modifier = Modifier.testTag("nearby_manufacturer_${target.id}"),
                    )
                }
            }
        }
        unavailable?.let { target ->
            Text("${target.label}: ${uiText("Programos atidaryti nepavyko")}", color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("nearby_manufacturer_unavailable"))
            Text("${uiText("Paieška")}: ${target.settingsSearch}", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { settingsFailed = !ManufacturerSharingActions.openSettings(context) }, enabled = enabled,
                modifier = Modifier.testTag("nearby_manufacturer_settings")) {
                LText("Atidaryti nustatymus")
            }
            if (settingsFailed) LText("Veiksmas nepavyko", color = MaterialTheme.colorScheme.error)
        }
    }
}
