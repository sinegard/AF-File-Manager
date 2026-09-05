package com.affilemanager.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification
import com.affilemanager.app.ui.AfWorkflowTab
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.components.AfModalDialog
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.workflow.AfAutomationRule
import com.affilemanager.app.workflow.AfAutomationSchedule
import com.affilemanager.app.workflow.AfExecutionStatus
import com.affilemanager.app.workflow.AfLocationKind
import com.affilemanager.app.workflow.AfOperationReceipt
import com.affilemanager.app.workflow.AfPlanDefinition
import com.affilemanager.app.workflow.AfPreflightDisposition
import com.affilemanager.app.workflow.AfTimelineSearch
import com.affilemanager.app.workflow.AfUndoDisposition
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AfWorkflowDialog(viewModel: MainViewModel) {
    val ui by viewModel.afWorkflowUi.collectAsStateWithLifecycle()
    val snapshot by viewModel.afWorkflowSnapshot.collectAsStateWithLifecycle()
    val clipboard by viewModel.afClipboard.collectAsStateWithLifecycle()
    if (!ui.open) return

    var automationPlan by remember { mutableStateOf<AfPlanDefinition?>(null) }
    var pendingJson by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingText by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = LocalContext.current
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = pendingJson
        if (uri != null && pending != null) {
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(pending.second) }
        }
        pendingJson = null
    }
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val pending = pendingText
        if (uri != null && pending != null) {
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(pending.second) }
        }
        pendingText = null
    }

    Dialog(
        onDismissRequest = viewModel::closeAfWorkflowCenter,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                LText("AF planai", fontWeight = FontWeight.Bold)
                                LText("Pirmiausia peržiūra, tada saugus vykdymas", style = MaterialTheme.typography.labelSmall)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = viewModel::closeAfWorkflowCenter) {
                                Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti"))
                            }
                        },
                    )
                },
            ) { padding ->
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WorkflowTabChip(ui.tab == AfWorkflowTab.PLANS, "Planai", Icons.Rounded.AutoAwesomeMotion) {
                            viewModel.setAfWorkflowTab(AfWorkflowTab.PLANS)
                        }
                        WorkflowTabChip(ui.tab == AfWorkflowTab.TIMELINE, "Istorija", Icons.Rounded.History) {
                            viewModel.setAfWorkflowTab(AfWorkflowTab.TIMELINE)
                        }
                        WorkflowTabChip(ui.tab == AfWorkflowTab.AUTOMATION, "Automatika", Icons.Rounded.Schedule) {
                            viewModel.setAfWorkflowTab(AfWorkflowTab.AUTOMATION)
                        }
                    }
                    HorizontalDivider()
                    when (ui.tab) {
                        AfWorkflowTab.PLANS -> PlansContent(viewModel, ui, snapshot.plans, clipboard?.sources?.size ?: 0) {
                            viewModel.clearAfAutomationPreview()
                            automationPlan = it
                        }
                        AfWorkflowTab.TIMELINE -> TimelineContent(
                            viewModel = viewModel,
                            ui = ui,
                            receipts = snapshot.timeline,
                            onExportJson = { receipt ->
                                val value = viewModel.afReceiptJson(receipt.id)
                                val name = "af-receipt-${receipt.id.take(12)}.json"
                                pendingJson = name to value
                                jsonLauncher.launch(name)
                            },
                            onExportText = { receipt ->
                                val value = viewModel.afReceiptText(receipt.id)
                                val name = "af-receipt-${receipt.id.take(12)}.txt"
                                pendingText = name to value
                                textLauncher.launch(name)
                            },
                        )
                        AfWorkflowTab.AUTOMATION -> AutomationContent(viewModel, snapshot.automations, snapshot.plans)
                    }
                }
            }
        }
    }

    automationPlan?.let { plan ->
        AutomationDialog(
            plan = plan,
            ui = ui,
            onDismiss = {
                viewModel.clearAfAutomationPreview()
                automationPlan = null
            },
            onPreview = { viewModel.previewAfAutomation(plan) },
            onSave = { name, schedule, unmetered, charging ->
                viewModel.createAfAutomation(plan, name, schedule, unmetered, charging)
                automationPlan = null
            },
        )
    }
}

@Composable
private fun WorkflowTabChip(selected: Boolean, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { LText(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

@Composable
private fun PlansContent(
    viewModel: MainViewModel,
    ui: com.affilemanager.app.ui.AfWorkflowUiState,
    plans: List<AfPlanDefinition>,
    clipboardCount: Int,
    onAutomate: (AfPlanDefinition) -> Unit,
) {
    val editing = ui.sources.isNotEmpty() || ui.destinations.isNotEmpty() || ui.name.isNotBlank() || ui.editingPlanId != null
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("af_plans_list"),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::newAfPlanDraft, enabled = clipboardCount > 0, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    LText("Naujas iš kopijavimo rinkinio ($clipboardCount)")
                }
                OutlinedButton(onClick = viewModel::newAfPlanDraft) {
                    Icon(Icons.Rounded.Add, contentDescription = uiText("Naujas planas"))
                }
            }
        }
        if (editing) {
            item { PlanEditor(viewModel, ui) }
            ui.preview?.let { preview -> item { PreflightCard(viewModel, preview, ui.working) } }
        }
        item { WorkflowSectionHeader("Išsaugoti planai", plans.size.toString()) }
        if (plans.isEmpty()) {
            item { WorkflowInfoCard("Planų dar nėra", "Nukopijuokite failus, pridėkite vieną ar kelias paskirtis ir pirmiausia peržiūrėkite rezultatą.") }
        } else {
            items(plans, key = AfPlanDefinition::id) { plan ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(plan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        LText("Šaltiniai: ${plan.sources.size} · Paskirtys: ${plan.destinations.size}", style = MaterialTheme.typography.bodySmall)
                        LText(
                            "${verificationLabel(plan.verification)} · ${conflictLabel(plan.conflictPolicy)}" +
                                if (plan.deleteSourcesAfterVerifiedCopies) " · šalinti tik patvirtinus visas kopijas" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { viewModel.editAfPlan(plan) }) { LText("Atidaryti") }
                            OutlinedButton(onClick = { onAutomate(plan) }) { LText("Automatizuoti") }
                            IconButton(onClick = { viewModel.removeAfPlan(plan.id) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = uiText("Pašalinti planą"))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanEditor(viewModel: MainViewModel, ui: com.affilemanager.app.ui.AfWorkflowUiState) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("af_plan_editor"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LText(if (ui.editingPlanId == null) "Naujas AF planas" else "Redaguojamas AF planas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = ui.name,
                onValueChange = viewModel::setAfPlanName,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { LText("Plano pavadinimas") },
            )
            LText("Šaltiniai (${ui.sources.size})", fontWeight = FontWeight.SemiBold)
            ui.sources.take(20).forEachIndexed { index, source ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (source.location.kind == AfLocationKind.LOCAL) Icons.Rounded.Folder else Icons.Rounded.Storage, contentDescription = null)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(source.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(source.location.displayLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { viewModel.removeAfSource(index) }) {
                        Icon(Icons.Rounded.Close, contentDescription = uiText("Pašalinti šaltinį"))
                    }
                }
            }
            if (ui.sources.size > 20) LText("Dar ${ui.sources.size - 20} šaltinių", style = MaterialTheme.typography.labelSmall)
            LText("Paskirtys (${ui.destinations.size})", fontWeight = FontWeight.SemiBold)
            ui.destinations.forEachIndexed { index, destination ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (destination.location.kind == AfLocationKind.LOCAL) Icons.Rounded.Folder else Icons.Rounded.Storage, contentDescription = null)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(destination.location.displayLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { viewModel.toggleAfDestinationRequired(index) }, contentPadding = PaddingValues(0.dp)) {
                            LText(if (destination.required) "Privaloma paskirtis" else "Neprivaloma paskirtis")
                        }
                    }
                    IconButton(onClick = { viewModel.removeAfDestination(index) }) {
                        Icon(Icons.Rounded.Close, contentDescription = uiText("Pašalinti paskirtį"))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedButton(onClick = viewModel::addActiveLocalAfDestination, modifier = Modifier.weight(1f)) {
                    LText("Pridėti aktyvų telefono aplanką")
                }
                OutlinedButton(onClick = viewModel::addCurrentRemoteAfDestination, modifier = Modifier.weight(1f)) {
                    LText("Pridėti atvertą serverio aplanką")
                }
            }
            LText("Konfliktai", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ConflictPolicy.KEEP_BOTH, ConflictPolicy.SKIP, ConflictPolicy.REPLACE, ConflictPolicy.MERGE).forEach { policy ->
                    FilterChip(
                        selected = ui.conflictPolicy == policy,
                        onClick = { viewModel.setAfPlanConflictPolicy(policy) },
                        label = { LText(conflictLabel(policy)) },
                    )
                }
            }
            LText("Patikra", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.verification == TransferVerification.SIZE,
                    onClick = { viewModel.setAfPlanVerification(TransferVerification.SIZE) },
                    label = { LText("Dydis") },
                )
                FilterChip(
                    selected = ui.verification == TransferVerification.SHA256,
                    onClick = { viewModel.setAfPlanVerification(TransferVerification.SHA256) },
                    label = { Text("SHA-256") },
                )
                FilterChip(
                    selected = ui.failurePolicy == TransferFailurePolicy.SKIP_AND_CONTINUE,
                    onClick = {
                        viewModel.setAfPlanFailurePolicy(
                            if (ui.failurePolicy == TransferFailurePolicy.STOP) TransferFailurePolicy.SKIP_AND_CONTINUE else TransferFailurePolicy.STOP,
                        )
                    },
                    enabled = !ui.deleteSourcesAfterVerifiedCopies && ui.conflictPolicy != ConflictPolicy.REPLACE,
                    label = { LText("Praleisti klaidą ir tęsti") },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    LText("Šaltinius šalinti tik patvirtinus visas privalomas kopijas", fontWeight = FontWeight.SemiBold)
                    LText("Jei nors viena privaloma paskirtis nepavyks, šaltiniai liks nepakeisti.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = ui.deleteSourcesAfterVerifiedCopies, onCheckedChange = viewModel::setAfPlanDeleteSources)
            }
            ui.error?.let { ErrorText(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::saveAfPlan, enabled = !ui.working) {
                    Icon(Icons.Rounded.Save, contentDescription = null)
                    Spacer(Modifier.size(5.dp))
                    LText("Išsaugoti")
                }
                Button(
                    onClick = viewModel::previewAfPlan,
                    enabled = !ui.working && ui.sources.isNotEmpty() && ui.destinations.isNotEmpty() && ui.name.isNotBlank(),
                    modifier = Modifier.testTag("af_plan_preview"),
                ) {
                    if (ui.working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.size(5.dp))
                    LText("Peržiūrėti planą")
                }
            }
        }
    }
}

@Composable
private fun PreflightCard(viewModel: MainViewModel, preview: com.affilemanager.app.workflow.AfPreflightSummary, working: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("af_preflight"),
        colors = CardDefaults.cardColors(
            containerColor = if (preview.canRun) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (preview.canRun) Icons.Rounded.CheckCircle else Icons.Rounded.Error, contentDescription = null)
                LText(if (preview.canRun) "Planas paruoštas" else "Planą reikia pataisyti", modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
            }
            LText("Elementai: ${preview.entries.size} · Kopijos: ${preview.readyCopies} · Konfliktai: ${preview.conflicts}")
            LText(
                "Šaltinių dydis: ${FileSystemRules.humanBytes(preview.totalSourceBytes)} · Numatoma įrašyti: ${FileSystemRules.humanBytes(preview.projectedWriteBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (preview.verifiedIdentical > 0) LText("Jau vienodi: ${preview.verifiedIdentical}", style = MaterialTheme.typography.bodySmall)
            if (preview.skipped > 0) LText("Bus praleista: ${preview.skipped}", style = MaterialTheme.typography.bodySmall)
            preview.blockers.forEach { ErrorText(it) }
            preview.warnings.forEach { LText("Įspėjimas: $it", style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = viewModel::runAfPlan,
                enabled = preview.canRun && !working,
                modifier = Modifier.fillMaxWidth().testTag("af_plan_run"),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                LText("Vykdyti patvirtintą planą")
            }
        }
    }
}

@Composable
private fun TimelineContent(
    viewModel: MainViewModel,
    ui: com.affilemanager.app.ui.AfWorkflowUiState,
    receipts: List<AfOperationReceipt>,
    onExportJson: (AfOperationReceipt) -> Unit,
    onExportText: (AfOperationReceipt) -> Unit,
) {
    val query = ui.timelineQuery.trim()
    val filtered = remember(receipts, query) {
        if (query.isBlank()) receipts else {
            val traced = AfTimelineSearch.trace(receipts, query)
            val planMatches = receipts.filter { it.planName.contains(query, ignoreCase = true) }
            (traced + planMatches).distinctBy(AfOperationReceipt::id).sortedByDescending(AfOperationReceipt::finishedAtMillis)
        }
    }
    val selected = receipts.firstOrNull { it.id == ui.selectedReceiptId }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("af_timeline_list"),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = ui.timelineQuery,
                onValueChange = viewModel::setAfTimelineQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                label = { LText("Kur dingo mano failas?") },
                supportingText = { LText("Ieškoma tik AF File Manager atliktų operacijų istorijoje.") },
            )
        }
        if (selected != null) {
            item {
                ReceiptDetail(viewModel, ui, selected, onExportJson, onExportText)
            }
        }
        if (filtered.isEmpty()) {
            item { WorkflowInfoCard("Istorija tuščia", "Čia bus saugomi AF planų, automatizavimo ir saugaus atšaukimo kvitai.") }
        } else {
            items(filtered, key = AfOperationReceipt::id) { receipt ->
                Card(onClick = { viewModel.selectAfReceipt(receipt.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, contentDescription = null)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(receipt.planName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(receipt.finishedAtMillis))} · ${executionStatusLabel(receipt.status)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            LText("Įrašai: ${receipt.items.size} · Klaidos: ${receipt.errorCount}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptDetail(
    viewModel: MainViewModel,
    ui: com.affilemanager.app.ui.AfWorkflowUiState,
    receipt: AfOperationReceipt,
    onExportJson: (AfOperationReceipt) -> Unit,
    onExportText: (AfOperationReceipt) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(receipt.planName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { viewModel.selectAfReceipt(null) }) { Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti kvitą")) }
            }
            LText("Būsena: ${executionStatusLabel(receipt.status)} · Patikra: ${verificationLabel(receipt.verification)}")
            LText("Šaltiniai pašalinti: ${if (receipt.sourcesDeleted) "Taip" else "Ne"}", style = MaterialTheme.typography.bodySmall)
            receipt.errors.forEach { ErrorText(it) }
            receipt.items.take(50).forEach { item ->
                Column {
                    Text("${uiText(receiptItemStatusLabel(item.status))} · ${item.source.path}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    item.destination?.let { Text("→ ${it.displayLabel}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (receipt.items.size > 50) LText("Dar ${receipt.items.size - 50} įrašų rasite eksportuotame kvite.", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onExportJson(receipt) }) { LText("Eksportuoti JSON") }
                OutlinedButton(onClick = { onExportText(receipt) }) { LText("Eksportuoti tekstą") }
            }
            if (receipt.undoAvailable) {
                Button(onClick = { viewModel.previewAfUndo(receipt.id) }, enabled = !ui.working) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null)
                    Spacer(Modifier.size(5.dp))
                    LText("Peržiūrėti saugų atšaukimą")
                }
            }
            ui.undoPreview?.takeIf { it.receiptId == receipt.id }?.let { preview ->
                HorizontalDivider()
                LText(if (preview.canRun) "Atšaukimą vykdyti saugu" else "Atšaukimas sustabdytas", fontWeight = FontWeight.Bold)
                preview.items.take(30).forEach { item ->
                    LText(
                        "${undoLabel(item.disposition)} · ${item.location.displayLabel} · ${uiText(item.detail)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = viewModel::runAfUndo, enabled = preview.canRun) { LText("Atšaukti operaciją") }
            }
            ui.error?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun AutomationContent(viewModel: MainViewModel, rules: List<AfAutomationRule>, plans: List<AfPlanDefinition>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("af_automation_list"),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            WorkflowInfoCard(
                "Saugi automatika",
                "Taisyklė vykdoma tik tada, kai naujas planas tiksliai sutampa su paskutine jūsų patvirtinta peržiūra. Pasikeitus failams reikės naujo patvirtinimo.",
            )
        }
        if (rules.isEmpty()) {
            item { WorkflowInfoCard("Taisyklių dar nėra", "Atverkite išsaugotą planą ir pasirinkite „Automatizuoti“.") }
        } else {
            items(rules, key = AfAutomationRule::id) { rule ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, contentDescription = null)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(rule.name, fontWeight = FontWeight.SemiBold)
                            Text("${uiText(scheduleLabel(rule.schedule))} · ${plans.firstOrNull { it.id == rule.planId }?.name ?: uiText("Planas neberastas")}", style = MaterialTheme.typography.bodySmall)
                            rule.lastStatus?.let { LText(it, style = MaterialTheme.typography.labelSmall) }
                        }
                        Switch(checked = rule.enabled, onCheckedChange = { viewModel.setAfAutomationEnabled(rule, it) })
                        IconButton(onClick = { viewModel.removeAfAutomation(rule.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = uiText("Pašalinti automatiką"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutomationDialog(
    plan: AfPlanDefinition,
    ui: com.affilemanager.app.ui.AfWorkflowUiState,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onSave: (String, AfAutomationSchedule, Boolean, Boolean) -> Unit,
) {
    var name by remember(plan.id) { mutableStateOf("${plan.name} automation") }
    var schedule by remember { mutableStateOf(AfAutomationSchedule.DAILY) }
    var unmetered by remember { mutableStateOf(true) }
    var charging by remember { mutableStateOf(false) }
    val preview = ui.automationPreview?.takeIf { it.plan.id == plan.id }
    AfModalDialog(
        title = "Automatizuoti AF planą",
        icon = Icons.Rounded.Schedule,
        onDismissRequest = onDismiss,
        expandedContent = true,
        modifier = Modifier.testTag("automation_dialog"),
        actions = {
            TextButton(onClick = onDismiss) { LText("Atšaukti") }
            if (preview == null) {
                Button(onClick = onPreview, enabled = name.isNotBlank() && !ui.working) {
                    if (ui.working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.size(5.dp))
                    LText("Peržiūrėti automatikos planą")
                }
            } else {
                Button(
                    onClick = { onSave(name, schedule, unmetered, charging) },
                    enabled = name.isNotBlank() && preview.canRun && !ui.working,
                ) {
                    LText("Patvirtinti ir įjungti")
                }
            }
        },
    ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(120) }, label = { LText("Taisyklės pavadinimas") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AfAutomationSchedule.EVERY_6_HOURS, AfAutomationSchedule.DAILY, AfAutomationSchedule.WEEKLY).forEach { option ->
                        FilterChip(selected = schedule == option, onClick = { schedule = option }, label = { LText(scheduleLabel(option)) })
                    }
                }
                ToggleRow("Tik nematuojamas tinklas", unmetered) { unmetered = it }
                ToggleRow("Tik įkraunant", charging) { charging = it }
                LText("Pirmiausia peržiūrėkite konkretų darbą. Jei šaltiniai ar paskirtys vėliau pasikeis, automatinis vykdymas sustos.", style = MaterialTheme.typography.bodySmall)
                preview?.let { reviewed ->
                    HorizontalDivider()
                    LText(
                        if (reviewed.canRun) "Automatikos planas paruoštas" else "Automatikos planas sustabdytas",
                        fontWeight = FontWeight.Bold,
                    )
                    LText("Elementai: ${reviewed.entries.size} · Kopijos: ${reviewed.readyCopies} · Konfliktai: ${reviewed.conflicts}")
                    LText(
                        "Numatoma įrašyti: ${FileSystemRules.humanBytes(reviewed.projectedWriteBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    reviewed.projections.take(12).forEach { projection ->
                        Text(projection.targetRoot.displayLabel, style = MaterialTheme.typography.labelSmall)
                        LText(preflightDispositionLabel(projection.disposition), style = MaterialTheme.typography.labelSmall)
                    }
                    if (reviewed.projections.size > 12) {
                        LText("Dar ${reviewed.projections.size - 12} paskirčių veiksmų", style = MaterialTheme.typography.labelSmall)
                    }
                    reviewed.blockers.forEach { ErrorText(it) }
                    reviewed.warnings.forEach { LText("Įspėjimas: $it", style = MaterialTheme.typography.bodySmall) }
                }
                ui.error?.let { ErrorText(it) }
            }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LText(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WorkflowInfoCard(title: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LText(title, fontWeight = FontWeight.SemiBold)
            LText(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WorkflowSectionHeader(title: String, count: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LText(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(count, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorText(value: String) {
    LText(value, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun conflictLabel(policy: ConflictPolicy): String = when (policy) {
    ConflictPolicy.KEEP_BOTH -> "Palikti abu"
    ConflictPolicy.SKIP -> "Praleisti"
    ConflictPolicy.REPLACE -> "Pakeisti su atsargine kopija"
    ConflictPolicy.MERGE -> "Sujungti aplankus"
    ConflictPolicy.ASK -> "Klausti"
}

private fun scheduleLabel(schedule: AfAutomationSchedule): String = when (schedule) {
    AfAutomationSchedule.MANUAL_ONLY -> "Tik rankiniu būdu"
    AfAutomationSchedule.EVERY_6_HOURS -> "Kas 6 val."
    AfAutomationSchedule.DAILY -> "Kasdien"
    AfAutomationSchedule.WEEKLY -> "Kas savaitę"
}

private fun undoLabel(disposition: AfUndoDisposition): String = when (disposition) {
    AfUndoDisposition.SAFE -> "Saugu"
    AfUndoDisposition.CHANGED -> "Failas pasikeitė"
    AfUndoDisposition.MISSING -> "Failo nebėra"
    AfUndoDisposition.UNSUPPORTED -> "Negalima atšaukti"
}

private fun verificationLabel(verification: TransferVerification): String = when (verification) {
    TransferVerification.SIZE -> "Dydis"
    TransferVerification.SHA256 -> "SHA-256"
}

private fun preflightDispositionLabel(disposition: AfPreflightDisposition): String = when (disposition) {
    AfPreflightDisposition.READY -> "Bus kopijuojama"
    AfPreflightDisposition.VERIFIED_IDENTICAL -> "Jau vienoda ir patvirtinta"
    AfPreflightDisposition.POSSIBLY_IDENTICAL -> "Tikriausiai jau vienoda"
    AfPreflightDisposition.KEEP_BOTH -> "Bus paliktos abi versijos"
    AfPreflightDisposition.SKIP -> "Bus praleista"
    AfPreflightDisposition.REPLACE -> "Bus pakeista su atsargine kopija"
    AfPreflightDisposition.BLOCKED -> "Veiksmas užblokuotas"
}

private fun executionStatusLabel(status: AfExecutionStatus): String = when (status) {
    AfExecutionStatus.PREVIEWED -> "Peržiūrėta"
    AfExecutionStatus.QUEUED -> "Eilėje"
    AfExecutionStatus.RUNNING -> "Vykdoma"
    AfExecutionStatus.PAUSED -> "Pristabdyta"
    AfExecutionStatus.COMPLETED -> "Baigta"
    AfExecutionStatus.COMPLETED_WITH_ERRORS -> "Baigta su klaidomis"
    AfExecutionStatus.FAILED -> "Nepavyko"
    AfExecutionStatus.CANCELLED -> "Atšaukta"
    AfExecutionStatus.INTERRUPTED -> "Nutrūko"
}

private fun receiptItemStatusLabel(status: com.affilemanager.app.workflow.AfReceiptItemStatus): String = when (status) {
    com.affilemanager.app.workflow.AfReceiptItemStatus.COPIED -> "Nukopijuota"
    com.affilemanager.app.workflow.AfReceiptItemStatus.VERIFIED_IDENTICAL -> "Patvirtinta kaip vienoda"
    com.affilemanager.app.workflow.AfReceiptItemStatus.SKIPPED -> "Praleista"
    com.affilemanager.app.workflow.AfReceiptItemStatus.REPLACED -> "Pakeista"
    com.affilemanager.app.workflow.AfReceiptItemStatus.FAILED -> "Nepavyko"
    com.affilemanager.app.workflow.AfReceiptItemStatus.DELETED -> "Pašalintas šaltinis"
    com.affilemanager.app.workflow.AfReceiptItemStatus.RESTORED -> "Atkurta"
}
