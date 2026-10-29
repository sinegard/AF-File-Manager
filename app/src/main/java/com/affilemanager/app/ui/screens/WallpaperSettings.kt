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
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.dp
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
internal fun WallpaperSettings(settings: AppearanceSettings, onWallpaper: (Uri?) -> Unit, onTransparency: (Int) -> Unit,
    onShading: (Int) -> Unit = {}, onTransparentMenus: (Boolean) -> Unit = {}) {
    var chooseCrop by remember { androidx.compose.runtime.mutableStateOf(false) }
    var cropUri by remember { androidx.compose.runtime.mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { if (chooseCrop) cropUri = uri else onWallpaper(uri) }
    }
    var transparency by remember(settings.cardTransparency) { mutableFloatStateOf(settings.cardTransparency.toFloat()) }
    var shading by remember(settings.wallpaperShading) { mutableFloatStateOf(settings.wallpaperShading.toFloat()) }
    Column {
        AfActionRow {
            com.affilemanager.app.ui.components.CropChooseButton("Fono paveikslėlis",
                onClick = { chooseCrop = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onCrop = { chooseCrop = true; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.testTag("wallpaper_choose"))
            TextButton(onClick = { chooseCrop = true; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.testTag("wallpaper_crop")) { LText("Apkirpti") }
            if (settings.wallpaperRevision != 0L) TextButton(onClick = { onWallpaper(null) },
                modifier = Modifier.testTag("wallpaper_remove")) { LText("Pašalinti") }
        }
        if (settings.wallpaperRevision != 0L) {
            Text("${uiText("Fono pritemdymas")}: ${shading.roundToInt()}%")
            Slider(value = shading, onValueChange = { shading = it }, valueRange = 0f..100f,
                onValueChangeFinished = { onShading(shading.roundToInt()) },
                modifier = Modifier.fillMaxWidth().testTag("wallpaper_shading"))
        }
        Text("${uiText("Kortelių skaidrumas")}: ${transparency.roundToInt()}%")
        Slider(value = transparency, onValueChange = { transparency = it }, valueRange = 0f..100f,
            onValueChangeFinished = { onTransparency(transparency.roundToInt()) },
            modifier = Modifier.fillMaxWidth().testTag("card_transparency"))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val label = uiText("Skaidrūs meniu ir dialogai")
            val layout = rememberTextMeasurer().measure(label, LocalTextStyle.current)
            val inkWidth = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
            val inline = with(LocalDensity.current) { inkWidth <= (maxWidth - 64.dp).toPx() }
            val toggle: @Composable () -> Unit = {
                Switch(checked = settings.transparentMenus, onCheckedChange = onTransparentMenus,
                    modifier = Modifier.testTag("transparent_menus"))
            }
            if (inline) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LText("Skaidrūs meniu ir dialogai", modifier = Modifier.weight(1f))
                    toggle()
                }
            } else {
                // Keep the full translated label, including fallback-font overhang,
                // instead of squeezing it into the switch's remaining row width.
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    LText("Skaidrūs meniu ir dialogai", modifier = Modifier.fillMaxWidth())
                    toggle()
                }
            }
        }
    }
    cropUri?.let { com.affilemanager.app.ui.components.ImageCropDialog(it, "wallpaper", null,
        onDismiss = { cropUri = null }, onCropped = { cropped -> cropUri = null; onWallpaper(cropped) }) }
}
