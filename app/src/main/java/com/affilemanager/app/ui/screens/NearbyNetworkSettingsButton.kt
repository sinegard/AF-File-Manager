package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.affilemanager.app.transfer.NetworkSettingsActions
import com.affilemanager.app.transfer.NetworkSettingsTarget
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.theme.AfDropdownMenu

@Composable
internal fun NearbyNetworkSettingsButton(label: String = "Atidaryti Wi-Fi ir prieigos taško nustatymus") {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box {
            OutlinedButton(onClick = { expanded = true; failed = false },
                modifier = Modifier.fillMaxWidth().testTag("nearby_network_settings")) {
                Icon(Icons.Rounded.WifiTethering, contentDescription = null)
                LText(label, modifier = Modifier.padding(start = 7.dp))
            }
            AfDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                NetworkSettingsTarget.entries.forEach { target ->
                    DropdownMenuItem(text = {
                        when (target) {
                            NetworkSettingsTarget.WIFI -> LText("Atidaryti Wi-Fi nustatymus")
                            NetworkSettingsTarget.HOTSPOT -> LText("Atidaryti Wi-Fi ir prieigos taško nustatymus")
                            NetworkSettingsTarget.WIFI_DIRECT -> LText("Atidaryti Wi-Fi Direct nustatymus")
                        }
                    }, onClick = { expanded = false; failed = NetworkSettingsActions.open(context, target).isFailure },
                        modifier = Modifier.testTag("nearby_network_${target.name}"))
                }
            }
        }
        if (failed) LText("Veiksmas nepavyko", color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("nearby_network_settings_error"))
    }
}
