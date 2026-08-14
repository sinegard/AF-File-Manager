package com.affilemanager.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SyncSchedule(
    val id: String,
    val profileId: String,
    val profileName: String,
    val localRoot: String,
    val remoteRoot: String,
    val mode: SyncMode,
    val conflictPolicy: SyncConflictPolicy,
    val intervalHours: Long,
    val unmeteredOnly: Boolean,
    val lastRunAtMillis: Long? = null,
    val lastStatus: String? = null,
)

class SyncScheduleRepository(private val context: Context) {
    companion object {
        private const val PREFS = "sync_schedules_v1"
        private const val KEY_SCHEDULES = "schedules"
        private const val MAX_SCHEDULES = 25
        private const val MAX_RECORD_BYTES = 1_000_000
        private const val MIN_INTERVAL_HOURS = 1L
        private const val MAX_INTERVAL_HOURS = 24L * 30
        internal const val INPUT_ID = "schedule_id"
    }

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val workManager = WorkManager.getInstance(context)

    fun list(): List<SyncSchedule> = read().sortedBy { it.profileName.lowercase() }

    fun find(id: String): SyncSchedule? = read().firstOrNull { it.id == id }

    fun save(schedule: SyncSchedule): SyncSchedule {
        validate(schedule)
        val normalized = schedule.copy(id = schedule.id.ifBlank { UUID.randomUUID().toString() })
        val schedules = read().toMutableList()
        val index = schedules.indexOfFirst { it.id == normalized.id }
        if (index >= 0) schedules[index] = normalized else schedules += normalized
        require(schedules.size <= MAX_SCHEDULES) { "Sinchronizavimo tvarkaraščių riba viršyta" }
        write(schedules)
        enqueue(normalized)
        return normalized
    }

    fun updateResult(id: String, status: String) {
        val schedules = read().toMutableList()
        val index = schedules.indexOfFirst { it.id == id }
        if (index < 0) return
        schedules[index] = schedules[index].copy(lastRunAtMillis = System.currentTimeMillis(), lastStatus = status.take(500))
        write(schedules)
    }

    fun remove(id: String) {
        val schedules = read()
        val updated = schedules.filterNot { it.id == id }
        require(updated.size != schedules.size) { "Tvarkaraštis neberastas" }
        write(updated)
        workManager.cancelUniqueWork(uniqueName(id))
    }

    fun restoreWork() {
        read().forEach(::enqueue)
    }

    private fun enqueue(schedule: SyncSchedule) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (schedule.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(schedule.intervalHours, TimeUnit.HOURS)
            .setInputData(Data.Builder().putString(INPUT_ID, schedule.id).build())
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .addTag("af-file-manager-sync")
            .build()
        workManager.enqueueUniquePeriodicWork(uniqueName(schedule.id), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun read(): List<SyncSchedule> {
        val raw = preferences.getString(KEY_SCHEDULES, "[]") ?: "[]"
        require(raw.length <= MAX_RECORD_BYTES) { "Tvarkaraščių įrašas per didelis" }
        val array = JSONArray(raw)
        require(array.length() <= MAX_SCHEDULES) { "Tvarkaraščių riba viršyta" }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            SyncSchedule(
                id = item.getString("id"),
                profileId = item.getString("profileId"),
                profileName = item.getString("profileName"),
                localRoot = item.getString("localRoot"),
                remoteRoot = item.getString("remoteRoot"),
                mode = SyncMode.valueOf(item.getString("mode")),
                conflictPolicy = SyncConflictPolicy.valueOf(item.getString("conflictPolicy")),
                intervalHours = item.getLong("intervalHours"),
                unmeteredOnly = item.optBoolean("unmeteredOnly", true),
                lastRunAtMillis = item.optLong("lastRunAtMillis").takeIf { it > 0 },
                lastStatus = item.optString("lastStatus").ifBlank { null },
            ).also(::validate)
        }
    }

    private fun write(schedules: List<SyncSchedule>) {
        val array = JSONArray()
        schedules.forEach { schedule ->
            array.put(
                JSONObject()
                    .put("id", schedule.id)
                    .put("profileId", schedule.profileId)
                    .put("profileName", schedule.profileName)
                    .put("localRoot", schedule.localRoot)
                    .put("remoteRoot", schedule.remoteRoot)
                    .put("mode", schedule.mode.name)
                    .put("conflictPolicy", schedule.conflictPolicy.name)
                    .put("intervalHours", schedule.intervalHours)
                    .put("unmeteredOnly", schedule.unmeteredOnly)
                    .put("lastRunAtMillis", schedule.lastRunAtMillis ?: 0)
                    .put("lastStatus", schedule.lastStatus ?: ""),
            )
        }
        check(preferences.edit().putString(KEY_SCHEDULES, array.toString()).commit()) { "Tvarkaraščių įrašyti nepavyko" }
    }

    private fun validate(schedule: SyncSchedule) {
        require(schedule.profileId.isNotBlank()) { "Trūksta tinklo profilio" }
        require(schedule.localRoot.isNotBlank() && schedule.localRoot.length <= 4_096) { "Netinkamas vietinis kelias" }
        require(schedule.remoteRoot.isNotBlank() && schedule.remoteRoot.length <= 4_096) { "Netinkamas nuotolinis kelias" }
        require(schedule.intervalHours in MIN_INTERVAL_HOURS..MAX_INTERVAL_HOURS) { "Netinkamas kartojimo intervalas" }
    }

    private fun uniqueName(id: String) = "af-file-manager-sync-$id"
}
