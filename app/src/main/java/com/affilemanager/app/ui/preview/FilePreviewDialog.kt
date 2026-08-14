package com.affilemanager.app.ui.preview

import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.times
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.affilemanager.app.archive.ArchiveEntryInfo
import com.affilemanager.app.archive.ArchiveBrowserIndex
import com.affilemanager.app.MainActivity
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.PreviewTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date

private val pdfRenderPermits = Semaphore(1)

private data class PdfDocumentInfo(val pageAspectRatios: List<Float>) {
    val pageCount: Int get() = pageAspectRatios.size
}

@Composable
fun FilePreviewDialog(
    target: PreviewTarget,
    onClose: () -> Unit,
    onExtract: (FileEntry, CharArray?) -> Unit,
    onDecrypt: (FileEntry, CharArray) -> Unit,
) {
    val context = LocalContext.current
    val source = target.previewSource()
    var hash by remember(source.key) { mutableStateOf<String?>(null) }
    var hashRunning by remember { mutableStateOf(false) }
    var actionError by remember(source.key) { mutableStateOf<String?>(null) }
    var archivePath by remember(source.key) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val navigateBack: () -> Unit = {
        if (target is PreviewTarget.Archive && archivePath.isNotEmpty()) {
            archivePath = ArchiveBrowserIndex.parentOf(archivePath)
        } else {
            onClose()
        }
    }

    Dialog(onDismissRequest = navigateBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().testTag("file-preview-dialog"),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = uiText(if (archivePath.isEmpty()) "Uždaryti" else "Grįžti į ankstesnį archyvo aplanką"),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(source.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entrySummary(context, source), style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = {
                            runCatching { openWith(context, source) }
                                .onFailure { actionError = it.message ?: "Programų pasirinkiklio atidaryti nepavyko" }
                        },
                        modifier = Modifier.testTag("open-with-action"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        LText("Atidaryti su kita programa")
                    }
                    TextButton(
                        onClick = {
                            runCatching { shareFile(context, source) }
                                .onFailure { actionError = it.message ?: "Dalijimosi programos atidaryti nepavyko" }
                        },
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        LText("Dalintis")
                    }
                    TextButton(
                        onClick = {
                            hashRunning = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { sha256(context, source) } }
                                result.onSuccess { hash = it }.onFailure { actionError = it.message ?: "SHA-256 apskaičiuoti nepavyko" }
                                hashRunning = false
                            }
                        },
                    ) {
                        if (hashRunning) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Calculate, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        LText("SHA-256")
                    }
                }
                HorizontalDivider()
                hash?.let {
                    LText("SHA-256  $it", modifier = Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.labelSmall)
                }
                actionError?.let {
                    LText(it, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                when (target) {
                    is PreviewTarget.Archive -> ArchivePreview(
                        file = target.file,
                        entries = target.entries,
                        currentPath = archivePath,
                        onPathChanged = { archivePath = it },
                        onExtract = onExtract,
                    )
                    is PreviewTarget.Vault -> VaultPreview(target, onDecrypt)
                    is PreviewTarget.LocalFile, is PreviewTarget.TrashFile, is PreviewTarget.ContentFile -> FileContentPreview(source)
                }
            }
        }
    }
}

@Composable
private fun FileContentPreview(source: PreviewSource) {
    val context = LocalContext.current
    when {
        source.extension == "afvault" -> PropertiesPreview(source, "Šifruotas AF File Manager failas. Jį galima iššifruoti tik atidarius iš vietinės saugyklos.")
        source.kind == EntryKind.IMAGE -> ImagePreview(source)
        source.extension == "pdf" || source.mimeType(LocalContext.current) == "application/pdf" -> PdfPreview(source)
        source.kind == EntryKind.VIDEO || source.kind == EntryKind.AUDIO -> MediaPreview(source)
        source.kind == EntryKind.APK && source.localFile != null -> ApkPreview(requireNotNull(source.localFile))
        isEditableText(source, context) -> TextPreview(source)
        else -> PropertiesPreview(source)
    }
}

@Composable
private fun ImagePreview(source: PreviewSource) {
    val context = LocalContext.current
    val result by produceState<Result<Bitmap>?>(initialValue = null, source.key) {
        value = withContext(Dispatchers.IO) { runCatching { decodeBoundedBitmap(context, source) } }
    }
    val metadata by produceState(initialValue = emptyList<Pair<String, String>>(), source.key) {
        value = withContext(Dispatchers.IO) { runCatching { imageMetadata(context, source) }.getOrDefault(emptyList()) }
    }
    var scale by remember(source.key) { mutableFloatStateOf(PreviewZoomRules.MIN_SCALE) }
    var offset by remember(source.key) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(source.key) { mutableStateOf(IntSize.Zero) }
    @Suppress("DEPRECATION")
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = PreviewZoomRules.clamp(scale * zoomChange, PreviewZoomRules.IMAGE_MAX_SCALE)
        scale = nextScale
        offset = if (nextScale <= PreviewZoomRules.MIN_SCALE) Offset.Zero
        else boundedOffset(offset + panChange, viewportSize, nextScale)
    }
    val updateScale: (Float) -> Unit = { requested ->
        scale = PreviewZoomRules.clamp(requested, PreviewZoomRules.IMAGE_MAX_SCALE)
        offset = if (scale <= PreviewZoomRules.MIN_SCALE) Offset.Zero else boundedOffset(offset, viewportSize, scale)
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        ZoomControls(scale, PreviewZoomRules.IMAGE_MAX_SCALE, updateScale)
        when (val loaded = result) {
            null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            else -> {
                val bitmap = loaded.getOrNull()
                if (bitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clipToBounds()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .testTag("image-zoom-viewport")
                            .onSizeChanged { viewportSize = it }
                            .transformable(
                                state = transformState,
                                canPan = { scale > PreviewZoomRules.MIN_SCALE },
                                lockRotationOnZoomPan = true,
                            ),
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = source.name,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                            contentScale = ContentScale.Fit,
                        )
                    }
                    LText("${bitmap.width} × ${bitmap.height} px", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                } else {
                    PreviewLoadError(requireNotNull(loaded.exceptionOrNull()))
                }
            }
        }
        metadata.forEach { (label, value) -> PropertyRow(label, value) }
    }
}

@Composable
private fun PdfPreview(source: PreviewSource) {
    val context = LocalContext.current
    val documentInfoResult by produceState<Result<PdfDocumentInfo>?>(initialValue = null, source.key) {
        value = withContext(Dispatchers.IO) { runCatching { pdfDocumentInfo(context, source) } }
    }
    var scale by remember(source.key) { mutableFloatStateOf(PreviewZoomRules.MIN_SCALE) }
    var renderScale by remember(source.key) { mutableFloatStateOf(PreviewZoomRules.MIN_SCALE) }
    val horizontalScroll = rememberScrollState()
    @Suppress("DEPRECATION")
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        scale = PreviewZoomRules.clamp(scale * zoomChange, PreviewZoomRules.PDF_MAX_SCALE)
    }
    val updateScale: (Float) -> Unit = { scale = PreviewZoomRules.clamp(it, PreviewZoomRules.PDF_MAX_SCALE) }
    LaunchedEffect(scale) {
        if (scale <= PreviewZoomRules.MIN_SCALE) horizontalScroll.scrollTo(0)
        delay(140)
        renderScale = scale
    }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        ZoomControls(scale, PreviewZoomRules.PDF_MAX_SCALE, updateScale)
        when (val loaded = documentInfoResult) {
            null -> CircularProgressIndicator()
            else -> {
                val documentInfo = loaded.getOrNull()
                if (documentInfo == null) {
                    PreviewLoadError(requireNotNull(loaded.exceptionOrNull()))
                } else {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .transformable(
                                state = transformState,
                                canPan = { false },
                                lockRotationOnZoomPan = true,
                            ),
                    ) {
                        val viewportWidthPx = with(LocalDensity.current) { maxWidth.roundToPx().coerceAtLeast(1) }
                        val contentWidth = maxWidth * scale
                        Row(modifier = Modifier.fillMaxSize().horizontalScroll(horizontalScroll)) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(contentWidth)
                                    .testTag("pdf-continuous-pages"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                items(count = documentInfo.pageCount, key = { it }) { pageIndex ->
                                    PdfPageItem(
                                        context = context,
                                        source = source,
                                        pageIndex = pageIndex,
                                        pageAspectRatio = documentInfo.pageAspectRatios[pageIndex],
                                        viewportWidthPx = viewportWidthPx,
                                        renderScale = renderScale,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    context: android.content.Context,
    source: PreviewSource,
    pageIndex: Int,
    pageAspectRatio: Float,
    viewportWidthPx: Int,
    renderScale: Float,
) {
    var bitmap by remember(source.key, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var renderError by remember(source.key, pageIndex) { mutableStateOf<Throwable?>(null) }
    var rendering by remember(source.key, pageIndex) { mutableStateOf(true) }
    LaunchedEffect(source.key, pageIndex, viewportWidthPx, renderScale) {
        rendering = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                pdfRenderPermits.withPermit {
                    renderPdfPage(context, source, pageIndex, viewportWidthPx, renderScale)
                }
            }
        }
        result.onSuccess {
            bitmap = it
            renderError = null
        }.onFailure { renderError = it }
        rendering = false
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        when (val loadedBitmap = bitmap) {
            null -> if (renderError == null || rendering) Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(pageAspectRatio),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() } else PreviewLoadError(requireNotNull(renderError))
            else -> Image(
                    bitmap = loadedBitmap.asImageBitmap(),
                    contentDescription = uiText("PDF puslapis ${pageIndex + 1}"),
                    modifier = Modifier.fillMaxWidth().aspectRatio(loadedBitmap.width.toFloat() / loadedBitmap.height),
                    contentScale = ContentScale.Fit,
                )
        }
    }
}

@Composable
private fun ZoomControls(scale: Float, maximum: Float, onScaleChanged: (Float) -> Unit) {
    val zoomOutDescription = uiText("Atitolinti")
    val zoomInDescription = uiText("Priartinti")
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onScaleChanged(PreviewZoomRules.zoomOut(scale, maximum)) },
            enabled = scale > PreviewZoomRules.MIN_SCALE,
            modifier = Modifier.semantics { contentDescription = zoomOutDescription },
        ) { LText("−", style = MaterialTheme.typography.headlineSmall) }
        LText(
            "${PreviewZoomRules.percent(scale)} %",
            modifier = Modifier.padding(horizontal = 12.dp).testTag("zoom-level"),
            style = MaterialTheme.typography.labelLarge,
        )
        IconButton(
            onClick = { onScaleChanged(PreviewZoomRules.zoomIn(scale, maximum)) },
            enabled = scale < maximum,
            modifier = Modifier.semantics { contentDescription = zoomInDescription },
        ) { LText("+", style = MaterialTheme.typography.headlineSmall) }
        TextButton(onClick = { onScaleChanged(PreviewZoomRules.MIN_SCALE) }, enabled = scale > PreviewZoomRules.MIN_SCALE) {
            LText("Atstatyti")
        }
    }
}

@Composable
private fun PreviewLoadError(error: Throwable) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
        LText("Failo peržiūros sukurti nepavyko", style = MaterialTheme.typography.titleMedium)
        error.message?.takeIf(String::isNotBlank)?.let {
            LText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MediaPreview(source: PreviewSource) {
    val context = LocalContext.current
    val metadata by produceState(initialValue = emptyList<Pair<String, String>>(), source.key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    source.localFile?.let { retriever.setDataSource(it.absolutePath) }
                        ?: retriever.setDataSource(context, source.uri(context))
                    listOfNotNull(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { "Trukmė" to "${it / 1_000} s" },
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { "Tipas" to it },
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let { "Bitų sparta" to it },
                    )
                } finally {
                    retriever.release()
                }
            }.getOrDefault(emptyList())
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    val controls = MediaController(context)
                    controls.setAnchorView(this)
                    setMediaController(controls)
                    setVideoURI(source.uri(context))
                    setOnPreparedListener { seekTo(1) }
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f).background(androidx.compose.ui.graphics.Color.Black),
        )
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            metadata.forEach { (label, value) -> PropertyRow(label, value) }
        }
    }
}

@Composable
private fun TextPreview(source: PreviewSource) {
    val context = LocalContext.current
    val localFile = source.localFile
    var text by remember(source.key) { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var changed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(source.key) {
        val result = withContext(Dispatchers.IO) {
            runCatching { readBoundedText(context, source) }
        }
        result.onSuccess { text = it }.onFailure { status = it.message }
        loading = false
    }
    Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LText(
                if (localFile != null && source.isWritable) "UTF-8 · iki 2 MB" else "UTF-8 · iki 2 MB · tik skaityti",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (localFile != null && source.isWritable) {
                Button(
                    onClick = {
                        scope.launch {
                            status = withContext(Dispatchers.IO) { atomicWriteText(localFile, text).fold({ "Išsaugota" }, { it.message }) }
                            changed = false
                        }
                    },
                    enabled = changed,
                ) {
                    Icon(Icons.Rounded.Save, contentDescription = null)
                    LText("Išsaugoti", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        status?.let { LText(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        else OutlinedTextField(
            value = text,
            onValueChange = { updated ->
                if (localFile != null && source.isWritable) {
                    text = updated
                    changed = true
                }
            },
            modifier = Modifier.fillMaxSize(),
            readOnly = localFile == null || !source.isWritable,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun ApkPreview(file: File) {
    val context = LocalContext.current
    val info = remember(file.absolutePath) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else android.content.pm.PackageManager.GET_SIGNATURES,
        )
    }
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.InstallMobile, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        info?.packageName?.let {
            Text(it, style = MaterialTheme.typography.titleLarge)
        } ?: LText("APK informacija nepasiekiama", style = MaterialTheme.typography.titleLarge)
        info?.let { packageInfo ->
            @Suppress("DEPRECATION")
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
            PropertyRow("Versija", "${packageInfo.versionName ?: "?"} ($versionCode)")
            @Suppress("DEPRECATION")
            val certificate = if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
            } else {
                packageInfo.signatures?.firstOrNull()?.toByteArray()
            }
            certificate?.let {
                PropertyRow("Parašo SHA-256", MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) })
            }
        }
        LText("Diegimą visada patvirtina Android sistema. Programa negali jo atlikti tyliai.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { installApk(context, file) }) {
            Icon(Icons.Rounded.InstallMobile, contentDescription = null)
            LText("Atidaryti diegimo lange", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ArchivePreview(
    file: FileEntry,
    entries: List<ArchiveEntryInfo>,
    currentPath: String,
    onPathChanged: (String) -> Unit,
    onExtract: (FileEntry, CharArray?) -> Unit,
) {
    var askPassword by remember { mutableStateOf(false) }
    val browser = remember(entries) { ArchiveBrowserIndex.from(entries) }
    val visibleEntries = remember(browser, currentPath) { browser.children(currentPath) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Archive, contentDescription = null, modifier = Modifier.size(34.dp))
            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                if (currentPath.isEmpty()) {
                    LText("Archyvo pradžia", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(
                        ArchiveBrowserIndex.folderName(currentPath),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LText("${visibleEntries.size} elementų", style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = { askPassword = true }) { LText("Išpakuoti") }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (currentPath.isNotEmpty()) {
                item(key = "archive-up") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPathChanged(ArchiveBrowserIndex.parentOf(currentPath)) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("archive-up"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        LText("Aukštyn", modifier = Modifier.padding(start = 12.dp), fontWeight = FontWeight.Medium)
                    }
                    HorizontalDivider()
                }
            }
            items(visibleEntries, key = { it.path }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = entry.directory) {
                            if (entry.directory) onPathChanged(entry.path)
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("archive-entry-${entry.path}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.Description,
                        contentDescription = null,
                        tint = if (entry.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(entry.name, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!entry.directory && entry.sizeBytes >= 0) Text(FileSystemRules.humanBytes(entry.sizeBytes), style = MaterialTheme.typography.labelSmall)
                    if (entry.directory) Icon(Icons.Rounded.ChevronRight, contentDescription = uiText("Atidaryti aplanką"))
                }
                HorizontalDivider()
            }
        }
    }
    if (askPassword) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { LText("Išpakuoti archyvą") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LText("Slaptažodį palikite tuščią, jei archyvas nešifruotas.")
                    OutlinedTextField(value = password, onValueChange = { password = it }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = { onExtract(file, password.takeIf(String::isNotBlank)?.toCharArray()); password = ""; askPassword = false }) {
                    LText("Išpakuoti")
                }
            },
            dismissButton = { TextButton(onClick = { askPassword = false }) { LText("Atšaukti") } },
        )
    }
}

@Composable
private fun VaultPreview(target: PreviewTarget.Vault, onDecrypt: (FileEntry, CharArray) -> Unit) {
    var askPassword by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(target.header.originalName, style = MaterialTheme.typography.titleLarge)
        Text(FileSystemRules.humanBytes(target.header.originalSize))
        LText("AES-256-GCM · PBKDF2-HMAC-SHA256", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { askPassword = true }) { LText("Iššifruoti šalia originalo") }
    }
    if (askPassword) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askPassword = false },
            title = { LText("Įveskite slaptafrazę") },
            text = { OutlinedTextField(value = password, onValueChange = { password = it }, visualTransformation = PasswordVisualTransformation(), singleLine = true) },
            confirmButton = {
                Button(onClick = { onDecrypt(target.file, password.toCharArray()); password = ""; askPassword = false }, enabled = password.isNotEmpty()) {
                    LText("Iššifruoti")
                }
            },
            dismissButton = { TextButton(onClick = { askPassword = false }) { LText("Atšaukti") } },
        )
    }
}

@Composable
private fun PropertiesPreview(source: PreviewSource, note: String? = null) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        note?.let { LText(it, modifier = Modifier.padding(vertical = 10.dp)) }
        PropertyRow("Pavadinimas", source.name)
        PropertyRow("Vieta", source.locationLabel)
        PropertyRow("Dydis", source.sizeBytes?.let(FileSystemRules::humanBytes) ?: uiText("Nežinomas"))
        source.modifiedAtMillis?.let { PropertyRow("Pakeista", DateFormat.getDateTimeInstance().format(Date(it))) }
        PropertyRow("Skaitomas", uiText(if (source.isReadable) "Taip" else "Ne"))
        PropertyRow("Įrašomas", uiText(if (source.isWritable) "Taip" else "Ne"))
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        LText(label, modifier = Modifier.weight(0.35f), style = MaterialTheme.typography.labelMedium)
        Text(value, modifier = Modifier.weight(0.65f), style = MaterialTheme.typography.bodyMedium)
    }
    HorizontalDivider()
}

private fun pdfDocumentInfo(context: android.content.Context, source: PreviewSource): PdfDocumentInfo =
    source.openFileDescriptor(context).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount > 0) { "PDF neturi puslapių" }
            require(renderer.pageCount <= PdfRenderRules.MAX_PAGE_COUNT) { "PDF viršijo ${PdfRenderRules.MAX_PAGE_COUNT} puslapių saugos ribą" }
            PdfDocumentInfo(
                pageAspectRatios = List(renderer.pageCount) { pageIndex ->
                    renderer.openPage(pageIndex).use { page ->
                        page.width.toFloat() / page.height.coerceAtLeast(1)
                    }
                },
            )
        }
    }

private fun renderPdfPage(
    context: android.content.Context,
    source: PreviewSource,
    pageIndex: Int,
    viewportWidthPx: Int,
    zoom: Float,
): Bitmap {
    source.openFileDescriptor(context).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            require(renderer.pageCount in 1..PdfRenderRules.MAX_PAGE_COUNT) { "PDF puslapių skaičius nepalaikomas" }
            require(pageIndex in 0 until renderer.pageCount) { "PDF puslapis nepasiekiamas" }
            renderer.openPage(pageIndex).use { page ->
                val (width, height) = PdfRenderRules.pageSizeForViewport(
                    sourceWidth = page.width,
                    sourceHeight = page.height,
                    viewportWidthPx = viewportWidthPx,
                    zoom = zoom,
                )
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }
}

private fun decodeBoundedBitmap(context: android.content.Context, source: PreviewSource): Bitmap {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val imageSource = source.localFile?.let { ImageDecoder.createSource(it) }
            ?: ImageDecoder.createSource(context.contentResolver, source.uri(context))
        return ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
            var sample = 1
            while (info.size.width / sample > 2_048 || info.size.height / sample > 2_048) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    source.openFileDescriptor(context).use { descriptor ->
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
    }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Vaizdo perskaityti nepavyko" }
    var sample = 1
    while (bounds.outWidth / sample > 2_048 || bounds.outHeight / sample > 2_048) sample *= 2
    return requireNotNull(source.openFileDescriptor(context).use { descriptor ->
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }) {
        "Vaizdo perskaityti nepavyko"
    }
}

private fun imageMetadata(context: android.content.Context, source: PreviewSource): List<Pair<String, String>> {
    fun attributes(exif: ExifInterface) = listOfNotNull(
        exif.getAttribute(ExifInterface.TAG_MAKE)?.let { "Gamintojas" to it },
        exif.getAttribute(ExifInterface.TAG_MODEL)?.let { "Modelis" to it },
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { "Fotografuota" to it },
        exif.getAttribute(ExifInterface.TAG_ORIENTATION)?.let { "Orientacija" to it },
    )
    return source.localFile?.let { attributes(ExifInterface(it)) }
        ?: source.openFileDescriptor(context).use { attributes(ExifInterface(it.fileDescriptor)) }
}

private fun isEditableText(source: PreviewSource, context: android.content.Context): Boolean = source.extension in setOf(
    "txt", "md", "csv", "json", "xml", "yaml", "yml", "log", "html", "htm", "kt", "java", "py", "js", "ts", "css", "sh", "ini", "conf",
) || source.kind == EntryKind.DOCUMENT && source.mimeType(context).startsWith("text/")

private fun readBoundedText(context: android.content.Context, source: PreviewSource, maxBytes: Int = 2 * 1_024 * 1_024): String {
    source.sizeBytes?.let { require(it <= maxBytes) { "Failas per didelis vidiniam redaktoriui" } }
    val output = ByteArrayOutputStream(minOf(source.sizeBytes?.toInt() ?: 8_192, maxBytes))
    source.openInputStream(context).buffered().use { input ->
        val buffer = ByteArray(64 * 1_024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "Failas per didelis vidiniam redaktoriui" }
            output.write(buffer, 0, read)
        }
    }
    val bytes = output.toByteArray()
    return try {
        bytes.toString(Charsets.UTF_8)
    } finally {
        bytes.fill(0)
    }
}

private fun boundedOffset(candidate: Offset, viewport: IntSize, scale: Float): Offset {
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
}

private fun atomicWriteText(file: File, text: String): Result<Unit> = runCatching {
    val bytes = text.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 2 * 1_024 * 1_024) { "Turinys viršijo 2 MB ribą" }
    val temporary = File(file.parentFile, ".${file.name}.af.tmp")
    try {
        temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
        runCatching {
            java.nio.file.Files.move(
                temporary.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        bytes.fill(0)
        if (temporary.exists()) temporary.delete()
    }
}

private fun sha256(context: android.content.Context, source: PreviewSource): String {
    val digest = MessageDigest.getInstance("SHA-256")
    source.openInputStream(context).buffered().use { input ->
        val buffer = ByteArray(256 * 1_024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun shareFile(context: android.content.Context, source: PreviewSource) {
    val uri = source.uri(context)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = source.mimeType(context)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(source.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Dalintis failu",
        ),
    )
}

internal fun openWith(context: android.content.Context, source: PreviewSource) {
    val uri = source.uri(context)
    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, source.mimeType(context))
        clipData = ClipData.newRawUri(source.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(viewIntent, "Atidaryti „${source.name}“ su").apply {
        putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
    }
    context.startActivity(chooser)
}

private fun installApk(context: android.content.Context, file: File) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, "${context.packageName}.files", file),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

private fun entrySummary(context: android.content.Context, source: PreviewSource): String = listOfNotNull(
    source.sizeBytes?.let(FileSystemRules::humanBytes),
    source.modifiedAtMillis?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) },
    source.mimeType(context).takeIf { source.modifiedAtMillis == null },
).joinToString(" · ")
