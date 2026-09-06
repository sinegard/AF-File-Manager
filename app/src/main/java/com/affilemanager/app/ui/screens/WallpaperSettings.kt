package com.affilemanager.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.affilemanager.app.ui.components.AfActionRow
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.theme.AppearanceSettings
import kotlin.math.roundToInt

@Composable
internal fun WallpaperSettings(settings: AppearanceSettings, onWallpaper: (Uri?) -> Unit, onTransparency: (Int) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onWallpaper(uri)
    }
    var transparency by remember(settings.cardTransparency) { mutableFloatStateOf(settings.cardTransparency.toFloat()) }
    Column {
        AfActionRow {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.testTag("wallpaper_choose")) { LText("Fono paveikslėlis") }
            if (settings.wallpaperRevision != 0L) TextButton(onClick = { onWallpaper(null) },
                modifier = Modifier.testTag("wallpaper_remove")) { LText("Pašalinti") }
        }
        Text("${uiText("Kortelių skaidrumas")}: ${transparency.roundToInt()}%")
        Slider(value = transparency, onValueChange = { transparency = it }, valueRange = 0f..100f,
            onValueChangeFinished = { onTransparency(transparency.roundToInt()) },
            modifier = Modifier.fillMaxWidth().testTag("card_transparency"))
    }
}
