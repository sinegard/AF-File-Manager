package com.affilemanager.app.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.ui.AppLockOverlay
import com.affilemanager.app.ui.authenticate
import com.affilemanager.app.ui.components.AfActionRow
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.components.CanonicalPathText
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.screens.LocalUploadDialog
import com.affilemanager.app.ui.screens.SharedFolderPickerDialog
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import com.affilemanager.app.ui.theme.AfFilterChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class WidgetDraft : ViewModel() {
    var id = -1
    var config by mutableStateOf(AfWidgetConfig())
}

class AfWidgetConfigureActivity : AppCompatActivity() {
    private val draft: WidgetDraft by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.ensureEnglishDefault(this)
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (!AfWidgetProvider.owns(this, id)) { finish(); return }
        if (draft.id != id) { draft.id = id; draft.config = AfWidgetStore(this).load(id) ?: AfWidgetConfig() }
        val app = application as AFFileManagerApplication
        setContent {
            val appearance by app.graph.appearance.settings.collectAsStateWithLifecycle()
            val locked by app.graph.appLock.enabled.collectAsStateWithLifecycle()
            var unlocked by remember { mutableStateOf(!locked) }
            var error by remember { mutableStateOf<String?>(null) }
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, locked) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (locked && event == androidx.lifecycle.Lifecycle.Event.ON_STOP) unlocked = false
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            BackHandler { finish() }
            AFFileManagerTheme(appearance) {
                if (locked && !unlocked) AppLockOverlay(onUnlock = {
                    authenticate(this, UiTranslator.translate("Atrakinti AF File Manager", resources.configuration.locales[0].toLanguageTag()),
                        { unlocked = true }, { error = it })
                }, onCancel = { finish() }, message = error)
                else WidgetConfigurationForm(draft.config, { draft.config = it }, error, { finish() }) {
                    runCatching {
                        check(AfWidgetProvider.owns(this, id))
                        AfWidgetStore(this).save(id, draft.config)
                        AfWidgetProvider.refresh(this)
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                        finish()
                    }.onFailure { error = "Nepavyko išsaugoti valdiklio nustatymų" }
                }
            }
        }
    }
}

@Composable
internal fun WidgetConfigurationForm(config: AfWidgetConfig, onChange: (AfWidgetConfig) -> Unit,
    error: String?, onDismiss: () -> Unit, onSave: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as AFFileManagerApplication
    var pickFolder by remember { mutableStateOf(false) }
    var pickFiles by remember { mutableStateOf(false) }
    val roots by produceState<List<com.affilemanager.app.model.StorageRoot>>(emptyList()) { value = app.graph.localFiles.roots() }
    AfModalDialog("Valdiklis", Icons.Rounded.Widgets, onDismiss, expandedContent = true, decorFitsSystemWindows = false,
        compactHeightThreshold = 220.dp,
        modifier = Modifier.testTag("widget_configuration"), actions = {
            val initialFocus = com.affilemanager.app.ui.components.rememberDialogInitialFocusTarget()
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("widget_cancel").focusProperties { canFocus = true }.focusRequester(initialFocus)) { LText("Atšaukti") }
            TextButton(onClick = onSave, enabled = runCatching { config.validated() }.isSuccess,
                modifier = Modifier.testTag("widget_save")) { LText("Išsaugoti") }
        }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            error?.let { LText(it, color = MaterialTheme.colorScheme.error) }
            AfActionRow { AfWidgetKind.entries.forEach { kind ->
                AfFilterChip(config.kind == kind, { onChange(config.copy(kind = kind, index = 0)) },
                    label = { LText(kind.label) }, modifier = Modifier.testTag("widget_kind_${kind.name}"))
            } }
            OutlinedTextField(config.title, { onChange(config.copy(title = it.filterNot(Char::isISOControl).take(120))) },
                label = { LText("Pavadinimas") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("widget_title_input")
                    .then(com.affilemanager.app.ui.components.rememberKeyboardAwareInput()))
            if (config.kind == AfWidgetKind.STORAGE) AfActionRow { AfWidgetStorage.entries.forEach { storage ->
                AfFilterChip(config.storage == storage, { onChange(config.copy(storage = storage)) }, label = { LText(storage.label) },
                    modifier = Modifier.testTag("widget_storage_${storage.name}"))
            } }
            if (config.kind in setOf(AfWidgetKind.SHORTCUT, AfWidgetKind.SEARCH, AfWidgetKind.IMAGES, AfWidgetKind.VIDEO, AfWidgetKind.MUSIC)) {
                CanonicalPathText(config.folder, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                AfActionRow {
                    OutlinedButton(onClick = { pickFolder = true }, modifier = Modifier.testTag("widget_choose_folder")) { LText("Pasirinkti aplanką") }
                    if (config.kind != AfWidgetKind.SEARCH) OutlinedButton(onClick = { pickFiles = true }, modifier = Modifier.testTag("widget_choose_files")) { LText("Pasirinkti failus") }
                    if (config.sources.isNotEmpty()) TextButton(onClick = { onChange(config.copy(sources = emptyList(), index = 0)) }) { LText("Išvalyti") }
                }
                if (config.sources.isNotEmpty()) config.sources.forEach { Text(File(it).name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if (config.kind == AfWidgetKind.SEARCH) OutlinedTextField(config.query, { onChange(config.copy(query = it.take(512))) },
                    label = { LText("Ieškoti") }, modifier = Modifier.fillMaxWidth().testTag("widget_search_query")
                        .then(com.affilemanager.app.ui.components.rememberKeyboardAwareInput()))
            }
            LText("Fono nepermatomumas")
            Slider(config.opacity.toFloat(), { onChange(config.copy(opacity = it.toInt())) }, valueRange = 25f..100f, modifier = Modifier.testTag("widget_opacity"))
            LText("Sąsajos tankis")
            Slider(config.density.toFloat(), { onChange(config.copy(density = it.toInt())) }, valueRange = 75f..125f, modifier = Modifier.testTag("widget_density"))
            AfActionRow {
                AfFilterChip(!config.horizontal, { onChange(config.copy(horizontal = false)) }, label = { LText("Vertikaliai") })
                AfFilterChip(config.horizontal, { onChange(config.copy(horizontal = true)) }, label = { LText("Horizontaliai") })
            }
            if (config.kind in setOf(AfWidgetKind.IMAGES, AfWidgetKind.VIDEO, AfWidgetKind.MUSIC)) {
                LText("Pasukimas")
                AfActionRow { listOf(0, 90, 180, 270).forEach { angle ->
                    AfFilterChip(config.rotation == angle, { onChange(config.copy(rotation = angle)) }, label = { Text("$angle°") })
                } }
                LText("Aplanko valdiklis rodo iki 32 tinkamų failų iš pirmųjų 512 įrašų.", style = MaterialTheme.typography.bodySmall)
                if (config.kind == AfWidgetKind.IMAGES) {
                    LText("Nuotraukų keitimo intervalas")
                    AfActionRow { listOf(5, 15, 30, 60, 300).forEach { seconds ->
                        AfFilterChip(config.slideSeconds == seconds, { onChange(config.copy(slideSeconds = seconds)) }, label = { Text("$seconds s") })
                    } }
                    AfActionRow { AfWidgetTransition.entries.forEach { transition ->
                        AfFilterChip(config.transition == transition, { onChange(config.copy(transition = transition)) }, label = { LText(transition.label) })
                    } }
                    LText("Nuotraukos keičiasi, kol valdiklis matomas. Android aplanko turinį atnaujina kas 30 minučių arba paspaudus „Atnaujinti“.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LText("Greitis")
                    Slider(config.speed, { onChange(config.copy(speed = it)) }, valueRange = .5f..2f)
                }
                if (config.kind != AfWidgetKind.IMAGES) AfActionRow { Checkbox(config.repeat, { onChange(config.copy(repeat = it)) }); LText("Kartoti") }
            }
            LText("Valdiklio dydį ir ilgą paspaudimą valdo telefono pagrindinio ekrano programa.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (pickFolder) SharedFolderPickerDialog(config.folder,
        { app.graph.localFiles.list(it, false, SortMode.NAME, SortDirection.ASCENDING) }, { pickFolder = false },
        { onChange(config.copy(folder = it, sources = emptyList(), index = 0)); pickFolder = false }, title = "Pasirinkti aplanką", confirmLabel = "Pasirinkti")
    if (pickFiles) LocalUploadDialog(config.folder, "", emptyList(), config.sources.toSet(),
        { app.graph.localFiles.list(it, false, SortMode.NAME, SortDirection.ASCENDING) }, { pickFiles = false },
        { paths -> onChange(config.copy(sources = paths.take(AfWidgetConfig.MAX_SOURCES), index = 0)); pickFiles = false },
        title = "Pasirinkti failus", confirmLabel = "Pasirinkti", filesOnly = true,
        allowMultiple = config.kind != AfWidgetKind.SHORTCUT, selectionLimit = AfWidgetConfig.MAX_SOURCES, storageRoots = roots)
}
