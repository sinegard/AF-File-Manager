package com.affilemanager.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.operations.OperationContext
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException

class SyncWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    companion object {
        private const val MAX_BACKGROUND_ACTIONS = 10_000
        private const val MAX_BACKGROUND_TRANSFER_BYTES = 1L * 1_024 * 1_024 * 1_024
    }

    override suspend fun doWork(): Result {
        val graph = (applicationContext as AFFileManagerApplication).graph
        val id = inputData.getString(SyncScheduleRepository.INPUT_ID) ?: return Result.failure()
        val schedule = runCatching { graph.syncSchedules.find(id) }.getOrNull() ?: return Result.success()
        var client: com.affilemanager.app.network.RemoteClient? = null
        return try {
            val profile = graph.networkProfiles.list().firstOrNull { it.id == schedule.profileId }
                ?: throw IllegalStateException("Tinklo profilis pašalintas")
            graph.networkProfiles.secret(profile.id).getOrThrow().use { secret ->
                client = graph.remoteClients.connect(profile, secret)
            }
            val remote = requireNotNull(client)
            val localRoot = File(schedule.localRoot)
            val preview = graph.sync.preview(
                localRoot,
                schedule.remoteRoot,
                remote,
                schedule.mode,
                schedule.conflictPolicy,
            )
            require(preview.actions.none { it.type == SyncActionType.CONFLICT }) { "Rasta konfliktų; reikia rankinės peržiūros" }
            val actions = preview.actions.count { it.type != SyncActionType.SKIP }
            require(actions <= MAX_BACKGROUND_ACTIONS) { "Fono veiksmų riba viršyta; paleiskite rankiniu būdu" }
            require(preview.totalTransferBytes <= MAX_BACKGROUND_TRANSFER_BYTES) { "Fono 1 GB riba viršyta; paleiskite rankiniu būdu" }
            graph.sync.execute(preview, localRoot, schedule.remoteRoot, remote, OperationContext.background())
            graph.syncSchedules.updateResult(id, "Pavyko: $actions veiksmų")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            graph.syncSchedules.updateResult(id, "Nepavyko: ${error.message ?: error::class.java.simpleName}")
            if (error is IOException && runAttemptCount < 3) Result.retry() else Result.success()
        } finally {
            runCatching { client?.close() }
        }
    }
}
