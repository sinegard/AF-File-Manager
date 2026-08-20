package com.affilemanager.app.workflow

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeUnit

class AfAutomationScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)

    fun synchronize(rule: AfAutomationRule) {
        val workSpec = AfAutomationPolicy.workSpec(rule)
        if (workSpec == null) {
            cancel(rule.id)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (workSpec.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(workSpec.chargingOnly)
            .build()
        val request = PeriodicWorkRequestBuilder<AfAutomationWorker>(workSpec.intervalHours, TimeUnit.HOURS)
            .setInputData(Data.Builder().putString(INPUT_RULE_ID, rule.id).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(uniqueName(rule.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun restore(rules: List<AfAutomationRule>) = rules.forEach(::synchronize)
    fun cancel(id: String) = workManager.cancelUniqueWork(uniqueName(id))

    private fun uniqueName(id: String) = "af-file-manager-automation-$id"

    companion object {
        internal const val INPUT_RULE_ID = "af_automation_rule_id"
        private const val WORK_TAG = "af-file-manager-automation"
    }
}

class AfAutomationWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(AfAutomationScheduler.INPUT_RULE_ID) ?: return Result.failure()
        val graph = (applicationContext as AFFileManagerApplication).graph
        return try {
            graph.workflows.runApprovedAutomation(id)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (error is IOException && runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}
