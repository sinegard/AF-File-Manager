package com.affilemanager.app.transfer

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class NearbyTransferHistoryFile(
    val id: String,
    val relativePath: String,
    val sizeBytes: Long,
    val transferredBytes: Long,
    val status: TransferFileStatus,
    val outgoing: Boolean,
)

data class NearbyTransferHistorySession(
    val id: String,
    val peerName: String,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val files: List<NearbyTransferHistoryFile> = emptyList(),
    val totalFileCount: Int = 0,
    val totalBytes: Long = 0,
    val filesTruncated: Boolean = false,
    val messages: List<NearbyChatMessage> = emptyList(),
)

internal object NearbyTransferHistoryRules {
    const val MAX_SESSIONS = 20
    const val MAX_FILES_PER_SESSION = 2_000
    const val MAX_MESSAGES_PER_SESSION = NearbyChatController.MAX_VISIBLE_MESSAGES
    const val MAX_STORED_PATH_CHARS = 512

    fun mergeFiles(
        session: NearbyTransferHistorySession,
        files: List<TransferFileProgress>,
        outgoing: Boolean,
        nowMillis: Long,
    ): NearbyTransferHistorySession {
        val byId = session.files.associateByTo(linkedMapOf()) { it.id }
        files.asSequence().filter { it.status.isHistoryTerminal() }.forEach { file ->
            val id = "${if (outgoing) 'o' else 'i'}:${file.batchId}:${file.relativePath}"
            if (id in byId || byId.size < MAX_FILES_PER_SESSION) {
                byId[id] = NearbyTransferHistoryFile(
                    id = id,
                    relativePath = file.relativePath.takeLast(MAX_STORED_PATH_CHARS),
                    sizeBytes = file.sizeBytes.coerceAtLeast(0L),
                    transferredBytes = file.transferredBytes.coerceIn(0L, file.sizeBytes.coerceAtLeast(0L)),
                    status = file.status,
                    outgoing = outgoing,
                )
            }
        }
        val logicalCount = maxOf(session.totalFileCount, files.size, byId.size)
        val logicalBytes = maxOf(
            session.totalBytes,
            files.sumOf { it.sizeBytes.coerceAtLeast(0L) },
            byId.values.sumOf(NearbyTransferHistoryFile::sizeBytes),
        )
        return session.copy(
            updatedAtMillis = nowMillis,
            files = byId.values.toList(),
            totalFileCount = logicalCount,
            totalBytes = logicalBytes,
            filesTruncated = session.filesTruncated || logicalCount > MAX_FILES_PER_SESSION,
        )
    }

    fun appendMessage(
        session: NearbyTransferHistorySession,
        message: NearbyChatMessage,
    ): NearbyTransferHistorySession = session.copy(
        updatedAtMillis = message.timestampMillis,
        messages = (session.messages + message).takeLast(MAX_MESSAGES_PER_SESSION),
    )

    fun normalize(sessions: List<NearbyTransferHistorySession>): List<NearbyTransferHistorySession> = sessions
        .asSequence()
        .filter { it.id.isNotBlank() && it.peerName.isNotBlank() && it.startedAtMillis > 0L }
        .distinctBy(NearbyTransferHistorySession::id)
        .sortedByDescending(NearbyTransferHistorySession::updatedAtMillis)
        .take(MAX_SESSIONS)
        .map { session ->
            session.copy(
                peerName = session.peerName.take(NearbyPairing.MAX_NAME_LENGTH),
                files = session.files.take(MAX_FILES_PER_SESSION),
                messages = session.messages.takeLast(MAX_MESSAGES_PER_SESSION),
            )
        }
        .toList()
}

private class NearbyTransferHistoryRepository(context: Context) {
    companion object {
        private const val MAX_FILE_BYTES = 8 * 1_048_576L
    }

    private val file = AtomicFile(File(context.noBackupFilesDir, "nearby-transfer-history.json"))

    fun load(): List<NearbyTransferHistorySession> {
        val base = file.baseFile
        if (!base.exists()) return emptyList()
        require(base.isFile && base.length() in 1..MAX_FILE_BYTES) { "Perdavimų istorijos failas netinkamas" }
        val root = file.openRead().use { input -> JSONObject(input.readBytes().toString(Charsets.UTF_8)) }
        require(root.optInt("version") == 1) { "Perdavimų istorijos versija nepalaikoma" }
        val rows = root.optJSONArray("sessions") ?: return emptyList()
        require(rows.length() <= NearbyTransferHistoryRules.MAX_SESSIONS) { "Perdavimų istorija per didelė" }
        return NearbyTransferHistoryRules.normalize(List(rows.length()) { index -> decodeSession(rows.getJSONObject(index)) })
    }

    fun save(sessions: List<NearbyTransferHistorySession>) {
        val normalized = NearbyTransferHistoryRules.normalize(sessions)
        val rows = JSONArray()
        normalized.forEach { rows.put(encodeSession(it)) }
        val bytes = JSONObject().put("version", 1).put("sessions", rows).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_FILE_BYTES) { "Perdavimų istorija per didelė" }
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    fun clear() {
        file.delete()
        check(!file.baseFile.exists()) { "Perdavimų istorijos išvalyti nepavyko" }
    }

    private fun encodeSession(session: NearbyTransferHistorySession): JSONObject = JSONObject()
        .put("id", session.id)
        .put("peer", session.peerName)
        .put("started", session.startedAtMillis)
        .put("updated", session.updatedAtMillis)
        .put("ended", session.endedAtMillis ?: JSONObject.NULL)
        .put("fileCount", session.totalFileCount)
        .put("totalBytes", session.totalBytes)
        .put("truncated", session.filesTruncated)
        .put("files", JSONArray().apply {
            session.files.forEach { file ->
                put(JSONObject().put("id", file.id).put("path", file.relativePath).put("size", file.sizeBytes)
                    .put("transferred", file.transferredBytes).put("status", file.status.name).put("outgoing", file.outgoing))
            }
        })
        .put("messages", JSONArray().apply {
            session.messages.forEach { message ->
                put(JSONObject().put("id", message.id).put("sender", message.senderName).put("body", message.body)
                    .put("outgoing", message.outgoing).put("timestamp", message.timestampMillis))
            }
        })

    private fun decodeSession(row: JSONObject): NearbyTransferHistorySession {
        val files = row.optJSONArray("files") ?: JSONArray()
        val messages = row.optJSONArray("messages") ?: JSONArray()
        require(files.length() <= NearbyTransferHistoryRules.MAX_FILES_PER_SESSION) { "Perdavimų istorija per didelė" }
        require(messages.length() <= NearbyTransferHistoryRules.MAX_MESSAGES_PER_SESSION) { "Perdavimų istorija per didelė" }
        return NearbyTransferHistorySession(
            id = row.getString("id").take(80),
            peerName = row.getString("peer").take(NearbyPairing.MAX_NAME_LENGTH),
            startedAtMillis = row.getLong("started"),
            updatedAtMillis = row.getLong("updated"),
            endedAtMillis = row.optLong("ended").takeIf { !row.isNull("ended") && it > 0L },
            totalFileCount = row.optInt("fileCount").coerceAtLeast(0),
            totalBytes = row.optLong("totalBytes").coerceAtLeast(0L),
            filesTruncated = row.optBoolean("truncated"),
            files = List(files.length()) { index ->
                val item = files.getJSONObject(index)
                NearbyTransferHistoryFile(
                    id = item.getString("id").take(1_200),
                    relativePath = item.getString("path").takeLast(NearbyTransferHistoryRules.MAX_STORED_PATH_CHARS),
                    sizeBytes = item.getLong("size").coerceAtLeast(0L),
                    transferredBytes = item.getLong("transferred").coerceAtLeast(0L),
                    status = runCatching { TransferFileStatus.valueOf(item.getString("status")) }.getOrDefault(TransferFileStatus.FAILED),
                    outgoing = item.optBoolean("outgoing"),
                )
            },
            messages = List(messages.length()) { index ->
                val item = messages.getJSONObject(index)
                NearbyChatMessage(
                    id = item.getString("id").take(80),
                    senderName = item.getString("sender").take(NearbyPairing.MAX_NAME_LENGTH),
                    body = NearbyChatController.validate(item.getString("body")),
                    outgoing = item.optBoolean("outgoing"),
                    timestampMillis = item.getLong("timestamp"),
                )
            },
        )
    }
}

object NearbyTransferHistoryController {
    private val mutableState = MutableStateFlow<List<NearbyTransferHistorySession>>(emptyList())
    val state: StateFlow<List<NearbyTransferHistorySession>> = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private var repository: NearbyTransferHistoryRepository? = null
    private var currentSessionId: String? = null
    private var currentIdentity: String? = null
    private var lastOutgoingSignature: String? = null
    private var lastIncomingSignature: String? = null

    @Synchronized
    fun initialize(context: Context) {
        if (repository != null) return
        repository = NearbyTransferHistoryRepository(context.applicationContext)
        runCatching { repository?.load().orEmpty() }.fold(
            onSuccess = {
                mutableState.value = it
                mutableError.value = null
            },
            onFailure = {
                mutableState.value = emptyList()
                mutableError.value = it.message ?: "Perdavimų istorijos perskaityti nepavyko"
            },
        )
    }

    @Synchronized
    fun beginSession(pairing: NearbyPairing) {
        val identity = "${pairing.host}:${pairing.port}:${pairing.receiverName}"
        val now = System.currentTimeMillis()
        if (currentIdentity == identity && currentSessionId != null) {
            updateCurrent { it.copy(peerName = pairing.receiverName, updatedAtMillis = now, endedAtMillis = null) }
            return
        }
        currentIdentity = identity
        currentSessionId = UUID.randomUUID().toString()
        lastOutgoingSignature = null
        lastIncomingSignature = null
        val session = NearbyTransferHistorySession(
            id = requireNotNull(currentSessionId),
            peerName = pairing.receiverName,
            startedAtMillis = now,
            updatedAtMillis = now,
        )
        mutableState.value = NearbyTransferHistoryRules.normalize(listOf(session) + mutableState.value)
    }

    @Synchronized
    fun recordTransfer(state: NearbyTransferState, outgoing: Boolean) {
        if (state.status == NearbyTransferStatus.IDLE || state.files.isEmpty()) return
        ensureSession(state.receiverName)
        val terminalCount = state.files.count { it.status.isHistoryTerminal() }
        val signature = "$terminalCount:${state.files.size}:${state.status}"
        if (outgoing && signature == lastOutgoingSignature) return
        if (!outgoing && signature == lastIncomingSignature) return
        if (outgoing) lastOutgoingSignature = signature else lastIncomingSignature = signature
        val now = System.currentTimeMillis()
        updateCurrent { session -> NearbyTransferHistoryRules.mergeFiles(session, state.files, outgoing, now) }
        if (state.status in setOf(NearbyTransferStatus.COMPLETED, NearbyTransferStatus.CANCELLED, NearbyTransferStatus.ERROR)) persist()
    }

    @Synchronized
    fun recordReceive(progress: LanUploadProgress, peerName: String?) {
        if (progress.files.isEmpty()) return
        ensureSession(peerName)
        val status = if (progress.completed) NearbyTransferStatus.COMPLETED else NearbyTransferStatus.RUNNING
        recordTransfer(
            NearbyTransferState(
                status = status,
                receiverName = peerName,
                fileCount = progress.totalFiles,
                completedFiles = progress.files.count { it.status == TransferFileStatus.COMPLETED },
                totalBytes = progress.totalBytes,
                sentBytes = progress.receivedBytes,
                files = progress.files,
            ),
            outgoing = false,
        )
    }

    @Synchronized
    fun recordMessage(message: NearbyChatMessage) {
        ensureSession(message.senderName)
        updateCurrent { NearbyTransferHistoryRules.appendMessage(it, message) }
        persist()
    }

    @Synchronized
    fun endSession() {
        val now = System.currentTimeMillis()
        updateCurrent { it.copy(updatedAtMillis = now, endedAtMillis = now) }
        persist()
    }

    @Synchronized
    fun clear() {
        runCatching { repository?.clear() }.fold(
            onSuccess = {
                currentSessionId = null
                currentIdentity = null
                lastOutgoingSignature = null
                lastIncomingSignature = null
                mutableState.value = emptyList()
                mutableError.value = null
            },
            onFailure = { mutableError.value = it.message ?: "Perdavimų istorijos išvalyti nepavyko" },
        )
    }

    private fun ensureSession(peerName: String?) {
        if (currentSessionId != null) return
        val now = System.currentTimeMillis()
        currentSessionId = UUID.randomUUID().toString()
        currentIdentity = peerName.orEmpty()
        val session = NearbyTransferHistorySession(
            id = requireNotNull(currentSessionId),
            peerName = peerName?.takeIf(String::isNotBlank) ?: "Other phone",
            startedAtMillis = now,
            updatedAtMillis = now,
        )
        mutableState.value = NearbyTransferHistoryRules.normalize(listOf(session) + mutableState.value)
    }

    private fun updateCurrent(transform: (NearbyTransferHistorySession) -> NearbyTransferHistorySession) {
        val id = currentSessionId ?: return
        mutableState.value = NearbyTransferHistoryRules.normalize(
            mutableState.value.map { if (it.id == id) transform(it) else it },
        )
    }

    private fun persist() {
        runCatching { repository?.save(mutableState.value) }.fold(
            onSuccess = { mutableError.value = null },
            onFailure = { mutableError.value = it.message ?: "Perdavimų istorijos išsaugoti nepavyko" },
        )
    }
}

private fun TransferFileStatus.isHistoryTerminal(): Boolean =
    this in setOf(TransferFileStatus.COMPLETED, TransferFileStatus.FAILED, TransferFileStatus.CANCELLED)
