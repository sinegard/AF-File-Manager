package com.affilemanager.app.picker

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.ui.AppLockOverlay
import com.affilemanager.app.ui.authenticate
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.screens.LocalUploadDialog
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Returns user-selected local content, not the app's browsing state, credentials or private files. */
class FilePickerActivity : AppCompatActivity() {
    private val graph get() = (application as AFFileManagerApplication).graph
    private var unlocked by mutableStateOf(false)
    private var hasAccess by mutableStateOf(false)
    private var boundary by mutableStateOf<FilePickerBoundary?>(null)
    private var rootEntries by mutableStateOf<List<FileEntry>>(emptyList())
    private var error by mutableStateOf<String?>(null)
    private var warning by mutableStateOf<String?>(null)
    private var confirming by mutableStateOf(false)
    private var confirmation: Job? = null
    private val legacyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasAccess = hasFileAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppLanguageManager.ensureEnglishDefault(this)
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val request = parseRequest(intent) ?: run { finish(); return }
        enableEdgeToEdge()
        setContent {
            val appearance by graph.appearance.settings.collectAsState()
            val lockEnabled by graph.appLock.enabled.collectAsState()
            AFFileManagerTheme(settings = appearance) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (lockEnabled && !unlocked) {
                        AppLockOverlay(onUnlock = {
                            authenticate(this, translated("Atrakinti AF File Manager"), { unlocked = true }) { error = it }
                        }, onCancel = { finish() }, message = error)
                    } else {
                        PickerContent(request)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasAccess = hasFileAccess()
        if (graph.appLock.enabled.value) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        unlocked = false
        confirmation?.cancel()
        confirming = false
        super.onStop()
    }

    @Composable
    private fun PickerContent(request: FilePickerRequest) {
        LaunchedEffect(hasAccess) {
            if (!hasAccess) return@LaunchedEffect
            try {
                val roots = graph.localFiles.roots().take(32)
                val discovered = withContext(Dispatchers.IO) {
                    FilePickerBoundary(roots.map { File(it.path) })
                }
                rootEntries = withContext(Dispatchers.IO) {
                    roots.mapNotNull { root ->
                        ensureActive()
                        discovered.permitted(root.path)?.takeIf { it.isDirectory && it.canRead() }
                            ?.let { graph.localFiles.toEntry(it).copy(name = translated(root.title)) }
                    }
                }
                boundary = discovered
                if (rootEntries.isEmpty()) error = "Saugykla nepasiekiama"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Saugykla nepasiekiama"
            }
        }
        val allowed = boundary
        if (hasAccess && allowed != null) {
            LocalUploadDialog(
                initialDirectoryPath = "", remotePath = "", initialEntries = rootEntries,
                initiallySelected = emptySet(),
                loadDirectory = { path -> loadDirectory(path, allowed, request) },
                onDismiss = { finish() }, onCopy = { paths -> confirm(paths, allowed, request) },
                title = "AF File Manager", confirmLabel = "Pasirinkti",
                filesOnly = true, allowMultiple = request.allowMultiple, selectionLimit = request.selectionLimit,
                parentDirectory = allowed::parent, message = error ?: warning, confirming = confirming,
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (hasAccess && error == null) CircularProgressIndicator()
                LText(error ?: if (!hasAccess) "Leidimas nesuteiktas" else "Įkeliama…")
                if (!hasAccess) Button(onClick = ::requestFileAccess) { LText("Suteikti") }
                TextButton(onClick = { finish() }) { LText("Atšaukti") }
            }
        }
    }

    private suspend fun loadDirectory(path: String, allowed: FilePickerBoundary, request: FilePickerRequest): Result<List<FileEntry>> {
        error = null
        warning = null
        if (path.isEmpty()) return Result.success(rootEntries)
        return try {
            var truncated = false
            val entries = withContext(Dispatchers.IO) {
                val directory = allowed.permitted(path)
                require(directory != null && directory.isDirectory) { "Pasirinkta dokumentų vieta nepasiekiama" }
                graph.localFiles.listProgressively(directory.path, false, SortMode.NAME, SortDirection.ASCENDING) {
                    truncated = it.truncated
                }.getOrThrow().filter { entry ->
                    ensureActive()
                    val file = allowed.permitted(entry.absolutePath)
                    file != null && file.canRead() && (file.isDirectory || (file.isFile && request.accepts(mimeType(file))))
                }
            }
            if (truncated) warning = "Rezultatas sutrumpintas pasiekus saugos ribą"
            Result.success(entries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.failure(IllegalStateException("Aplanko turinio perskaityti nepavyko"))
        }
    }

    private fun confirm(paths: List<String>, allowed: FilePickerBoundary, request: FilePickerRequest) {
        if (confirming || (graph.appLock.enabled.value && !unlocked)) return
        confirming = true
        error = null
        confirmation = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val files = allowed.selected(paths, request, ::mimeType)
                    val uris = files.map { file ->
                        ensureActive()
                        // Opening read-only checks actual accessibility, not merely a directory entry flag.
                        file.inputStream().use { }
                        FileProvider.getUriForFile(this@FilePickerActivity, "$packageName.files", file)
                    }
                    Intent().apply {
                        data = uris.first()
                        clipData = ClipData.newUri(contentResolver, "AF File Manager", uris.first()).also { clip ->
                            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                        }
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                }
                ensureActive()
                if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                    (graph.appLock.enabled.value && !unlocked)) return@launch
                setResult(RESULT_OK, result)
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Pasirinkti failai nepasiekiami arba neatitinka prašomo tipo"
            } finally {
                confirming = false
            }
        }
    }

    private fun hasFileAccess(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun requestFileAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null)))
            else legacyPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } catch (_: Exception) {
            error = "Leidimas nesuteiktas"
        }
    }

    private fun translated(text: String) = UiTranslator.translate(text, resources.configuration.locales[0].language)

    internal companion object {
        fun parseRequest(intent: Intent): FilePickerRequest? = try {
            if (intent.action != Intent.ACTION_GET_CONTENT) null
            else {
                val extras = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                if (intent.hasExtra(Intent.EXTRA_MIME_TYPES) && extras == null) null
                else FilePickerRequest.parse(intent.type, extras?.toList(), intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
            }
        } catch (_: RuntimeException) { null }

        fun mimeType(file: File): String = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "application/octet-stream"
    }
}
