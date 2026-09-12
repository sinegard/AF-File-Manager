package com.affilemanager.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

enum class TrashRetentionPeriod(val days: Int?) {
    ONE_DAY(1),
    THREE_DAYS(3),
    SEVEN_DAYS(7),
    FOURTEEN_DAYS(14),
    THIRTY_DAYS(30),
    SIXTY_DAYS(60),
    NINETY_DAYS(90),
    NEVER(null),
}

class TrashRetentionSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): TrashRetentionPeriod = runCatching {
        TrashRetentionPeriod.valueOf(preferences.getString(KEY_PERIOD, null) ?: TrashRetentionPeriod.NEVER.name)
    }.getOrDefault(TrashRetentionPeriod.NEVER)

    fun save(period: TrashRetentionPeriod) {
        check(preferences.edit().putString(KEY_PERIOD, period.name).commit()) {
            "Šiukšliadėžės saugojimo laikotarpio išsaugoti nepavyko"
        }
    }

    private companion object {
        const val PREFS = "trash_retention_v1"
        const val KEY_PERIOD = "period"
    }
}

class TrashRetentionScheduler(context: Context) {
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)

    fun synchronize(period: TrashRetentionPeriod = TrashRetentionSettings(applicationContext).load()) {
        if (period == TrashRetentionPeriod.NEVER) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<TrashRetentionWorker>(1, TimeUnit.DAYS)
            .addTag(WORK_NAME)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val WORK_NAME = "af-file-manager-trash-retention"
    }
}

class TrashRetentionWorker(appContext: Context, parameters: WorkerParameters) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val period = TrashRetentionSettings(applicationContext).load()
        if (period == TrashRetentionPeriod.NEVER) return Result.success()
        val result = try {
            TrashRepository(applicationContext).deleteExpired(period)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        return when {
            result.failedItems == 0 -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}
