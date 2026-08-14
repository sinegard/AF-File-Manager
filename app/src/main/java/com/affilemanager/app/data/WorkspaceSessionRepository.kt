package com.affilemanager.app.data

import android.content.Context
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

private const val CURRENT_WORKSPACE_SCHEMA = 2

data class WorkspaceTab(
    val id: String,
    val path: String,
    val locked: Boolean = false,
    val backHistory: List<String> = emptyList(),
    val forwardHistory: List<String> = emptyList(),
    val sortMode: SortMode = SortMode.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val includeHidden: Boolean = false,
    val grid: Boolean = false,
)

data class PanelWorkspace(
    val tabs: List<WorkspaceTab>,
    val activeTabId: String,
    val closedTabs: List<WorkspaceTab> = emptyList(),
) {
    val activeTab: WorkspaceTab get() = tabs.first { it.id == activeTabId }
}

data class WorkspaceSession(
    val schemaVersion: Int = CURRENT_WORKSPACE_SCHEMA,
    val left: PanelWorkspace,
    val right: PanelWorkspace,
)

class WorkspaceSessionRepository(context: Context) {
    companion object {
        const val MAX_TABS_PER_PANEL = 16
        const val MAX_CLOSED_TABS = 8
        private const val MAX_HISTORY = 50
        private const val MAX_PATH_LENGTH = 4_096
        private const val MAX_FILE_BYTES = 1L * 1_024 * 1_024
        private val SAFE_ID = Regex("[A-Za-z0-9-]{1,80}")
    }

    private val file = File(context.filesDir, "workspace_session_v1.json")

    @Synchronized
    fun load(defaultLeftPath: String, defaultRightPath: String): WorkspaceSession {
        if (!file.isFile) return defaultSession(defaultLeftPath, defaultRightPath)
        require(file.length() in 1..MAX_FILE_BYTES) { "Darbo sesijos failo dydis netinkamas" }
        return runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val legacy = json.getInt("schemaVersion") < CURRENT_WORKSPACE_SCHEMA
            val session = parse(json)
            if (legacy) save(session)
            session
        }
            .getOrElse {
                val corrupt = File(file.parentFile, "workspace_session_v1.${System.currentTimeMillis()}.corrupt")
                file.renameTo(corrupt)
                defaultSession(defaultLeftPath, defaultRightPath)
            }
    }

    @Synchronized
    fun save(session: WorkspaceSession) {
        validate(session)
        val bytes = session.toJson().toString().toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() in 1..MAX_FILE_BYTES) { "Darbo sesija per didelė" }
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            runCatching {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            bytes.fill(0)
            if (temporary.exists()) temporary.delete()
        }
    }

    fun newTab(path: String): WorkspaceTab = WorkspaceTab(id = UUID.randomUUID().toString(), path = canonicalOrAbsolute(path))

    private fun defaultSession(left: String, right: String): WorkspaceSession {
        val leftTab = newTab(left)
        val rightTab = newTab(right)
        return WorkspaceSession(
            left = PanelWorkspace(listOf(leftTab), leftTab.id),
            right = PanelWorkspace(listOf(rightTab), rightTab.id),
        )
    }

    private fun parse(json: JSONObject): WorkspaceSession {
        val sourceSchema = json.getInt("schemaVersion")
        require(sourceSchema in 1..CURRENT_WORKSPACE_SCHEMA) { "Nepalaikoma darbo sesijos versija" }
        val resetLegacySort = sourceSchema == 1
        return WorkspaceSession(
            schemaVersion = CURRENT_WORKSPACE_SCHEMA,
            left = parsePanel(json.getJSONObject("left"), resetLegacySort),
            right = parsePanel(json.getJSONObject("right"), resetLegacySort),
        ).also(::validate)
    }

    private fun parsePanel(json: JSONObject, resetLegacySort: Boolean): PanelWorkspace {
        val tabs = json.getJSONArray("tabs").toTabs(resetLegacySort)
        val closed = json.optJSONArray("closedTabs")?.toTabs(resetLegacySort).orEmpty()
        return PanelWorkspace(tabs, json.getString("activeTabId"), closed)
    }

    private fun JSONArray.toTabs(resetLegacySort: Boolean): List<WorkspaceTab> {
        require(length() <= MAX_TABS_PER_PANEL) { "Kortelių riba viršyta" }
        return (0 until length()).map { index ->
            val item = getJSONObject(index)
            WorkspaceTab(
                id = item.getString("id"),
                path = item.getString("path"),
                locked = item.optBoolean("locked", false),
                backHistory = item.optJSONArray("backHistory")?.toStrings(MAX_HISTORY).orEmpty(),
                forwardHistory = item.optJSONArray("forwardHistory")?.toStrings(MAX_HISTORY).orEmpty(),
                sortMode = if (resetLegacySort) SortMode.NAME else runCatching {
                    SortMode.valueOf(item.optString("sortMode", SortMode.NAME.name))
                }.getOrDefault(SortMode.NAME),
                sortDirection = if (resetLegacySort) SortDirection.ASCENDING else runCatching {
                    SortDirection.valueOf(item.optString("sortDirection", SortDirection.ASCENDING.name))
                }.getOrDefault(SortDirection.ASCENDING),
                includeHidden = item.optBoolean("includeHidden", false),
                grid = item.optBoolean("grid", false),
            )
        }
    }

    private fun JSONArray.toStrings(limit: Int): List<String> {
        require(length() <= limit) { "Navigacijos istorijos riba viršyta" }
        return (0 until length()).map(::getString)
    }

    private fun WorkspaceSession.toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("left", left.toJson())
        .put("right", right.toJson())

    private fun PanelWorkspace.toJson(): JSONObject = JSONObject()
        .put("tabs", JSONArray().apply { tabs.forEach { put(it.toJson()) } })
        .put("activeTabId", activeTabId)
        .put("closedTabs", JSONArray().apply { closedTabs.forEach { put(it.toJson()) } })

    private fun WorkspaceTab.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("path", path)
        .put("locked", locked)
        .put("backHistory", JSONArray().apply { backHistory.forEach(::put) })
        .put("forwardHistory", JSONArray().apply { forwardHistory.forEach(::put) })
        .put("sortMode", sortMode.name)
        .put("sortDirection", sortDirection.name)
        .put("includeHidden", includeHidden)
        .put("grid", grid)

    private fun validate(session: WorkspaceSession) {
        require(session.schemaVersion == CURRENT_WORKSPACE_SCHEMA) { "Nepalaikoma darbo sesijos versija" }
        validatePanel(session.left)
        validatePanel(session.right)
    }

    private fun validatePanel(panel: PanelWorkspace) {
        require(panel.tabs.size in 1..MAX_TABS_PER_PANEL) { "Kortelių skaičius netinkamas" }
        require(panel.closedTabs.size <= MAX_CLOSED_TABS) { "Uždarytų kortelių riba viršyta" }
        require(panel.tabs.map(WorkspaceTab::id).distinct().size == panel.tabs.size) { "Kortelių tapatybės kartojasi" }
        require(panel.activeTabId in panel.tabs.map(WorkspaceTab::id)) { "Aktyvi kortelė nerasta" }
        (panel.tabs + panel.closedTabs).forEach(::validateTab)
    }

    private fun validateTab(tab: WorkspaceTab) {
        require(SAFE_ID.matches(tab.id)) { "Netinkama kortelės tapatybė" }
        validatePath(tab.path)
        require(tab.backHistory.size <= MAX_HISTORY && tab.forwardHistory.size <= MAX_HISTORY) { "Navigacijos istorijos riba viršyta" }
        tab.backHistory.forEach(::validatePath)
        tab.forwardHistory.forEach(::validatePath)
    }

    private fun validatePath(path: String) = require(path.isNotBlank() && path.length <= MAX_PATH_LENGTH && '\u0000' !in path) { "Netinkamas kortelės kelias" }
    private fun canonicalOrAbsolute(path: String): String = runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }
}
