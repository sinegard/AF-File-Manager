package com.affilemanager.app.ui.preview

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Save
import com.affilemanager.app.ui.theme.AfAlertDialog as AlertDialog
import com.affilemanager.app.ui.theme.AfButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.affilemanager.app.ui.theme.AfSurface as Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.affilemanager.app.ui.theme.AfDialog
import com.affilemanager.app.pdfsigning.PdfSignaturePlacement
import com.affilemanager.app.pdfsigning.SavedSignature
import com.affilemanager.app.pdfsigning.SignatureDrawing
import com.affilemanager.app.pdfsigning.SignatureLibraryRepository
import com.affilemanager.app.pdfsigning.SignaturePoint
import com.affilemanager.app.pdfsigning.SignatureStroke
import com.affilemanager.app.pdfsigning.VisualSignatureRules
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.SignatureLibraryUiState
import com.affilemanager.app.ui.components.AfModalDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private enum class PdfSignatureStep { DRAW, PLACE }
private enum class SignatureDragMode { MOVE, RESIZE }

internal val PdfSignaturePlacementSemanticsKey =
    SemanticsPropertyKey<PdfSignaturePlacement>("PdfSignaturePlacement")
private var SemanticsPropertyReceiver.pdfSignaturePlacement by PdfSignaturePlacementSemanticsKey

@Composable
internal fun PdfSignatureDialog(
    source: PreviewSource,
    applying: Boolean,
    error: String?,
    signatureLibrary: SignatureLibraryUiState,
    onRefreshSignatureLibrary: () -> Unit,
    onSaveSignature: (String, SignatureDrawing) -> Unit,
    onDeleteSavedSignature: (String) -> Unit,
    onApply: (SignatureDrawing, PdfSignaturePlacement) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var step by remember(source.key) { mutableStateOf(PdfSignatureStep.DRAW) }
    var drawing by remember(source.key) { mutableStateOf(SignatureDrawing(emptyList())) }
    var pageIndex by remember(source.key) { mutableStateOf(0) }
    var pageInput by remember(source.key) { mutableStateOf("1") }
    var placement by remember(source.key) { mutableStateOf<PdfSignaturePlacement?>(null) }
    var submitted by remember(source.key) { mutableStateOf(false) }
    var showSaveSignature by remember(source.key) { mutableStateOf(false) }
    var showSignatureLibrary by remember(source.key) { mutableStateOf(false) }
    var deleteSignature by remember(source.key) { mutableStateOf<SavedSignature?>(null) }
    val documentResult by produceState<Result<PdfDocumentInfo>?>(initialValue = null, source.key) {
        value = withContext(Dispatchers.IO) { runCatching { pdfDocumentInfo(context, source) } }
    }
    val documentInfo = documentResult?.getOrNull()
    val pageBitmapResult by produceState<Result<Bitmap>?>(
        initialValue = null,
        source.key,
        pageIndex,
        step,
    ) {
        value = if (step != PdfSignatureStep.PLACE || documentInfo == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    pdfRenderPermits.withPermit {
                        renderPdfPage(context, source, pageIndex, SIGNATURE_PREVIEW_WIDTH_PX, 1f)
                    }
                }
            }
        }
    }
    val pageBitmap = pageBitmapResult?.getOrNull()

    LaunchedEffect(pageIndex) { pageInput = (pageIndex + 1).toString() }
    LaunchedEffect(source.key) { onRefreshSignatureLibrary() }
    LaunchedEffect(pageIndex, pageBitmap?.width, pageBitmap?.height) {
        val bitmap = pageBitmap ?: return@LaunchedEffect
        placement = VisualSignatureRules.defaultPlacement(
            pageIndex = pageIndex,
            pageAspectRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1),
        )
    }
    LaunchedEffect(submitted, applying, error) {
        if (submitted && !applying && error != null) submitted = false
    }

    AfDialog(
        onDismissRequest = { if (!applying && !submitted) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        com.affilemanager.app.ui.theme.AppearancePage(
            modifier = Modifier.fillMaxSize().testTag("pdf-signature-dialog"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            if (step == PdfSignatureStep.PLACE && !applying && !submitted) step = PdfSignatureStep.DRAW
                            else if (!applying && !submitted) onDismiss()
                        },
                    ) {
                        Icon(
                            if (step == PdfSignatureStep.PLACE) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close,
                            contentDescription = uiText(if (step == PdfSignatureStep.PLACE) "Grįžti" else "Uždaryti"),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        LText("Pasirašyti PDF", fontWeight = FontWeight.SemiBold)
                        LText(
                            if (step == PdfSignatureStep.DRAW) "1 iš 2 · Nupieškite parašą" else "2 iš 2 · Padėkite parašą",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider()
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    LText(
                        "Tai matomas ranka pieštas žymuo, o ne kvalifikuotas kriptografinis elektroninis parašas.",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                when (step) {
                    PdfSignatureStep.DRAW -> SignatureDrawStep(
                        drawing = drawing,
                        enabled = !applying && !submitted,
                        library = signatureLibrary,
                        onDrawingChanged = { drawing = it },
                        onSaveRequested = { showSaveSignature = true },
                        onOpenLibrary = { showSignatureLibrary = true },
                        onNext = { step = PdfSignatureStep.PLACE },
                        onCancel = onDismiss,
                    )
                    PdfSignatureStep.PLACE -> SignaturePlacementStep(
                        documentResult = documentResult,
                        documentInfo = documentInfo,
                        pageBitmapResult = pageBitmapResult,
                        pageBitmap = pageBitmap,
                        pageIndex = pageIndex,
                        pageInput = pageInput,
                        placement = placement,
                        drawing = drawing,
                        applying = applying || submitted,
                        error = error,
                        onPageInputChanged = { input ->
                            pageInput = input.filter(Char::isDigit).take(5)
                            val requested = pageInput.toIntOrNull()?.minus(1)
                            if (requested != null && documentInfo != null && requested in 0 until documentInfo.pageCount) {
                                pageIndex = requested
                            }
                        },
                        onPageChanged = { requested ->
                            val pageCount = documentInfo?.pageCount ?: 0
                            if (requested in 0 until pageCount) pageIndex = requested
                        },
                        onPlacementChanged = { placement = it },
                        onApply = {
                            val currentPlacement = placement ?: return@SignaturePlacementStep
                            submitted = true
                            onApply(drawing, currentPlacement)
                        },
                    )
                }
            }
        }
    }

    if (showSaveSignature) {
        SaveSignatureDialog(
            enabled = !signatureLibrary.loading,
            onSave = { name ->
                onSaveSignature(name, drawing)
                showSaveSignature = false
            },
            onDismiss = { showSaveSignature = false },
        )
    }
    if (showSignatureLibrary) {
        SavedSignaturesDialog(
            state = signatureLibrary,
            onSelect = { saved ->
                drawing = saved.drawing
                showSignatureLibrary = false
            },
            onDelete = { deleteSignature = it },
            onRefresh = onRefreshSignatureLibrary,
            onDismiss = { showSignatureLibrary = false },
        )
    }
    deleteSignature?.let { saved ->
        AlertDialog(
            onDismissRequest = { deleteSignature = null },
            title = { LText("Pašalinti parašą?") },
            text = { androidx.compose.material3.Text(saved.name) },
            dismissButton = {
                TextButton(onClick = { deleteSignature = null }) { LText("Atšaukti") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSavedSignature(saved.id)
                        deleteSignature = null
                    },
                    enabled = !signatureLibrary.loading,
                    modifier = Modifier.testTag("saved-signature-delete-confirm"),
                ) { LText("Pašalinti") }
            },
        )
    }
}

@Composable
private fun SignatureDrawStep(
    drawing: SignatureDrawing,
    enabled: Boolean,
    library: SignatureLibraryUiState,
    onDrawingChanged: (SignatureDrawing) -> Unit,
    onSaveRequested: () -> Unit,
    onOpenLibrary: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LText("Pieškite pirštu arba rašikliu baltoje srityje.", style = MaterialTheme.typography.bodyMedium)
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val padWidth = if (maxWidth / VisualSignatureRules.ASPECT_RATIO <= maxHeight) {
                maxWidth
            } else {
                maxHeight * VisualSignatureRules.ASPECT_RATIO
            }
            SignaturePad(
                drawing = drawing,
                enabled = enabled,
                onDrawingChanged = onDrawingChanged,
                modifier = Modifier.width(padWidth).aspectRatio(VisualSignatureRules.ASPECT_RATIO),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = {
                    if (drawing.strokes.isNotEmpty()) {
                        onDrawingChanged(drawing.copy(strokes = drawing.strokes.dropLast(1)))
                    }
                },
                enabled = enabled && drawing.strokes.isNotEmpty(),
                modifier = Modifier.testTag("signature-undo"),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                LText("Anuliuoti")
            }
            OutlinedButton(
                onClick = { onDrawingChanged(SignatureDrawing(emptyList())) },
                enabled = enabled && !drawing.isEmpty,
                modifier = Modifier.testTag("signature-clear"),
            ) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                LText("Atstatyti")
            }
            OutlinedButton(
                onClick = onSaveRequested,
                enabled = enabled && !drawing.isEmpty && !library.loading &&
                    library.items.size < SignatureLibraryRepository.MAX_SIGNATURES,
                modifier = Modifier.testTag("signature-save-open"),
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                LText("Išsaugoti programėlėje")
            }
            OutlinedButton(
                onClick = onOpenLibrary,
                enabled = enabled,
                modifier = Modifier.testTag("signature-library-open"),
            ) {
                Icon(Icons.Rounded.Draw, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                LText("Išsaugoti parašai")
            }
        }
        library.error?.let { LText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = enabled) { LText("Atšaukti") }
            Button(
                onClick = onNext,
                enabled = enabled && !drawing.isEmpty,
                modifier = Modifier.testTag("signature-next"),
            ) { LText("Toliau") }
        }
    }
}

@Composable
private fun SaveSignatureDialog(
    enabled: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val normalizedName = name.trim()
    AfModalDialog(
        title = "Išsaugoti parašą",
        icon = Icons.Rounded.Save,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("signature-save-dialog"),
        actions = {
            TextButton(onClick = onDismiss) { LText("Atšaukti") }
            Button(
                onClick = { onSave(normalizedName) },
                enabled = enabled && normalizedName.isNotEmpty(),
                modifier = Modifier.testTag("signature-save-confirm"),
            ) { LText("Išsaugoti") }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LText(
                "Duomenys saugomi tik šios programos privačioje saugykloje.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = { candidate ->
                    name = candidate
                        .filterNot { it == '\u0000' || it.isISOControl() }
                        .take(SignatureLibraryRepository.MAX_NAME_LENGTH)
                },
                label = { LText("Pavadinimas") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("signature-save-name"),
            )
        }
    }
}

@Composable
private fun SavedSignaturesDialog(
    state: SignatureLibraryUiState,
    onSelect: (SavedSignature) -> Unit,
    onDelete: (SavedSignature) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AfModalDialog(
        title = "Išsaugoti parašai",
        icon = Icons.Rounded.Draw,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("saved-signatures-dialog"),
        expandedContent = true,
        actions = {
            TextButton(
                onClick = onRefresh,
                enabled = !state.loading,
                modifier = Modifier.testTag("saved-signatures-refresh"),
            ) { LText("Atnaujinti") }
            TextButton(onClick = onDismiss) { LText("Uždaryti") }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.items.isEmpty() && !state.loading) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .testTag("saved-signatures-empty"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.Draw,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LText("Parašų dar nėra", style = MaterialTheme.typography.titleMedium)
                    state.error?.let {
                        LText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("saved-signatures-list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        LText(
                            "Duomenys saugomi tik šios programos privačioje saugykloje.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.error?.let { message ->
                        item {
                            LText(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(state.items, key = SavedSignature::id) { saved ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                androidx.compose.material3.Text(
                                    text = saved.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                SignatureDrawingPreview(
                                    drawing = saved.drawing,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(VisualSignatureRules.ASPECT_RATIO),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextButton(
                                        onClick = { onDelete(saved) },
                                        enabled = !state.loading,
                                        modifier = Modifier.testTag("saved-signature-delete"),
                                    ) {
                                        Icon(Icons.Rounded.Delete, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        LText("Pašalinti")
                                    }
                                    Button(
                                        onClick = { onSelect(saved) },
                                        enabled = !state.loading,
                                        modifier = Modifier.testTag("saved-signature-select"),
                                    ) { LText("Pasirinkti") }
                                }
                            }
                        }
                    }
                }
            }
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).testTag("saved-signatures-loading"),
                )
            }
        }
    }
}

@Composable
private fun SignatureDrawingPreview(
    drawing: SignatureDrawing,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .background(Color.White, MaterialTheme.shapes.medium)
            .testTag("saved-signature-preview"),
    ) {
        drawing.strokes.forEach { stroke ->
            val first = stroke.points.firstOrNull() ?: return@forEach
            if (stroke.points.size == 1) {
                drawCircle(
                    Color.Black,
                    radius = 2.5.dp.toPx(),
                    center = Offset(first.x * size.width, first.y * size.height),
                )
            } else {
                val path = Path().apply {
                    moveTo(first.x * size.width, first.y * size.height)
                    stroke.points.drop(1).forEach { point ->
                        lineTo(point.x * size.width, point.y * size.height)
                    }
                }
                drawPath(
                    path,
                    Color.Black,
                    style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

@Composable
private fun SignaturePad(
    drawing: SignatureDrawing,
    enabled: Boolean,
    onDrawingChanged: (SignatureDrawing) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDrawing by rememberUpdatedState(drawing)
    val currentCallback by rememberUpdatedState(onDrawingChanged)
    Canvas(
        modifier = modifier
            .background(Color.White, MaterialTheme.shapes.medium)
            .testTag("signature-pad")
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val base = currentDrawing
                    if (base.strokes.size >= VisualSignatureRules.MAX_STROKES ||
                        base.pointCount >= VisualSignatureRules.MAX_POINTS
                    ) return@awaitEachGesture
                    val firstPoint = VisualSignatureRules.point(
                        down.position.x / size.width.coerceAtLeast(1),
                        down.position.y / size.height.coerceAtLeast(1),
                    )
                    val points = mutableListOf(firstPoint)
                    currentCallback(base.copy(strokes = base.strokes + SignatureStroke(points.toList())))
                    var previousPosition = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val point = VisualSignatureRules.point(
                            change.position.x / size.width.coerceAtLeast(1),
                            change.position.y / size.height.coerceAtLeast(1),
                        )
                        val previous = points.last()
                        val dx = point.x - previous.x
                        val dy = point.y - previous.y
                        if (base.pointCount + points.size < VisualSignatureRules.MAX_POINTS &&
                            dx * dx + dy * dy >= MIN_POINT_DISTANCE_SQUARED
                        ) {
                            points += point
                            currentCallback(
                                base.copy(
                                    strokes = base.strokes + SignatureStroke(points.toList()),
                                ),
                            )
                        }
                        if (change.position != previousPosition) change.consume()
                        previousPosition = change.position
                        if (!change.pressed) break
                    }
                }
            },
    ) {
        drawing.strokes.forEach { stroke ->
            val first = stroke.points.firstOrNull() ?: return@forEach
            if (stroke.points.size == 1) {
                drawCircle(Color.Black, radius = 3.5.dp.toPx(), center = Offset(first.x * size.width, first.y * size.height))
            } else {
                val path = Path().apply {
                    moveTo(first.x * size.width, first.y * size.height)
                    stroke.points.drop(1).forEach { point -> lineTo(point.x * size.width, point.y * size.height) }
                }
                drawPath(path, Color.Black, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun SignaturePlacementStep(
    documentResult: Result<PdfDocumentInfo>?,
    documentInfo: PdfDocumentInfo?,
    pageBitmapResult: Result<Bitmap>?,
    pageBitmap: Bitmap?,
    pageIndex: Int,
    pageInput: String,
    placement: PdfSignaturePlacement?,
    drawing: SignatureDrawing,
    applying: Boolean,
    error: String?,
    onPageInputChanged: (String) -> Unit,
    onPageChanged: (Int) -> Unit,
    onPlacementChanged: (PdfSignaturePlacement) -> Unit,
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            documentResult == null -> CircularProgressIndicator()
            documentInfo == null -> PreviewLoadError(requireNotNull(documentResult.exceptionOrNull()))
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    IconButton(onClick = { onPageChanged(pageIndex - 1) }, enabled = !applying && pageIndex > 0) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = uiText("Ankstesnis puslapis"))
                    }
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = onPageInputChanged,
                        label = { LText("Puslapis") },
                        singleLine = true,
                        enabled = !applying,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(105.dp).testTag("signature-page-input"),
                    )
                    LText("iš ${documentInfo.pageCount}", modifier = Modifier.padding(horizontal = 8.dp))
                    IconButton(
                        onClick = { onPageChanged(pageIndex + 1) },
                        enabled = !applying && pageIndex + 1 < documentInfo.pageCount,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = uiText("Kitas puslapis"))
                    }
                }
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        pageBitmapResult == null -> CircularProgressIndicator()
                        pageBitmap == null -> PreviewLoadError(requireNotNull(pageBitmapResult.exceptionOrNull()))
                        else -> {
                            val aspect = pageBitmap.width.toFloat() / pageBitmap.height.coerceAtLeast(1)
                            val pageWidth = if (maxWidth / aspect <= maxHeight) maxWidth else maxHeight * aspect
                            Box(
                                modifier = Modifier
                                    .width(pageWidth)
                                    .aspectRatio(aspect)
                                    .background(Color.White)
                                    .testTag("signature-page-preview"),
                            ) {
                                Image(
                                    bitmap = pageBitmap.asImageBitmap(),
                                    contentDescription = uiText("PDF puslapis ${pageIndex + 1}"),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds,
                                )
                                placement?.let { current ->
                                    SignaturePlacementOverlay(
                                        drawing = drawing,
                                        placement = current,
                                        pageAspectRatio = aspect,
                                        enabled = !applying,
                                        onPlacementChanged = onPlacementChanged,
                                    )
                                }
                            }
                        }
                    }
                }
                LText(
                    "Vilkite rėmelį į norimą vietą. Tempkite jo apatinį dešinį kampą dydžiui keisti.",
                    style = MaterialTheme.typography.bodySmall,
                )
                error?.let {
                    LText(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(
                    onClick = onApply,
                    enabled = !applying && pageBitmap != null && placement != null,
                    modifier = Modifier.testTag("signature-apply"),
                ) {
                    if (applying) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    LText(if (applying) "Įrašomas parašas…" else "Pridėti parašą")
                }
            }
        }
    }
}

@Composable
private fun SignaturePlacementOverlay(
    drawing: SignatureDrawing,
    placement: PdfSignaturePlacement,
    pageAspectRatio: Float,
    enabled: Boolean,
    onPlacementChanged: (PdfSignaturePlacement) -> Unit,
) {
    val currentPlacement by rememberUpdatedState(placement)
    val currentCallback by rememberUpdatedState(onPlacementChanged)
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("signature-overlay")
            .semantics { pdfSignaturePlacement = placement }
            .pointerInput(enabled, pageAspectRatio) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val initial = currentPlacement
                    val right = (initial.left + initial.width) * size.width
                    val bottom = (initial.top + initial.height) * size.height
                    val handleRadius = RESIZE_TOUCH_RADIUS.toPx()
                    val handleDx = down.position.x - right
                    val handleDy = down.position.y - bottom
                    val touchesHandle = handleDx * handleDx + handleDy * handleDy <= handleRadius * handleRadius
                    val touchesBox = down.position.x in (initial.left * size.width)..right &&
                        down.position.y in (initial.top * size.height)..bottom
                    val dragMode = when {
                        touchesHandle -> SignatureDragMode.RESIZE
                        touchesBox -> SignatureDragMode.MOVE
                        else -> null
                    } ?: return@awaitEachGesture
                    var previousPosition = down.position
                    var accumulatedX = 0f
                    var accumulatedY = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.position - previousPosition
                        if (delta != Offset.Zero) {
                            accumulatedX += delta.x / size.width.coerceAtLeast(1)
                            accumulatedY += delta.y / size.height.coerceAtLeast(1)
                            val updated = when (dragMode) {
                                SignatureDragMode.MOVE -> VisualSignatureRules.move(initial, accumulatedX, accumulatedY)
                                SignatureDragMode.RESIZE -> {
                                    val heightPerWidth = initial.height / initial.width.coerceAtLeast(0.0001f)
                                    val projectedWidthDelta =
                                        (accumulatedX + heightPerWidth * accumulatedY) /
                                            (1f + heightPerWidth * heightPerWidth)
                                    VisualSignatureRules.resizeFromBottomRight(
                                        initial,
                                        initial.width + projectedWidthDelta,
                                        pageAspectRatio,
                                    )
                                }
                            }
                            currentCallback(updated)
                            change.consume()
                        }
                        previousPosition = change.position
                        if (!change.pressed) break
                    }
                }
            },
    ) {
        val left = placement.left * size.width
        val top = placement.top * size.height
        val width = placement.width * size.width
        val height = placement.height * size.height
        drawRect(
            color = Color(0xFF006C5F),
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(width, height),
            style = Stroke(2.dp.toPx()),
        )
        drawing.strokes.forEach { stroke ->
            val first = stroke.points.firstOrNull() ?: return@forEach
            if (stroke.points.size == 1) {
                drawCircle(Color.Black, 2.dp.toPx(), Offset(left + first.x * width, top + first.y * height))
            } else {
                val path = Path().apply {
                    moveTo(left + first.x * width, top + first.y * height)
                    stroke.points.drop(1).forEach { point ->
                        lineTo(left + point.x * width, top + point.y * height)
                    }
                }
                drawPath(path, Color.Black, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        val handleCenter = Offset(left + width, top + height)
        drawCircle(Color.White, radius = RESIZE_HANDLE_RADIUS.toPx(), center = handleCenter)
        drawCircle(
            Color(0xFF006C5F),
            radius = RESIZE_HANDLE_RADIUS.toPx(),
            center = handleCenter,
            style = Stroke(3.dp.toPx()),
        )
    }
}

private const val SIGNATURE_PREVIEW_WIDTH_PX = 1_200
private const val MIN_POINT_DISTANCE_SQUARED = 0.000004f
private val RESIZE_HANDLE_RADIUS = 8.dp
private val RESIZE_TOUCH_RADIUS = 24.dp
