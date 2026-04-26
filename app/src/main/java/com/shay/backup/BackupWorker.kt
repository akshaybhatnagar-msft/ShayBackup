package com.shay.backup

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        makeForegroundInfo(0, 0, "")

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val config = ConfigStore(ctx)
        AppInsights.trackTrace("backup.start", mapOf(
            "isPeriodic" to (tags.contains(UNIQUE_PERIODIC)).toString()
        ))
        if (!config.isConfigured) {
            AppInsights.trackTrace("backup.notConfigured", severity = 2)
            NotificationHelper.postFailure(ctx, "Azure target is not configured. Open Settings.")
            return@withContext Result.failure()
        }

        try {
            setForeground(makeForegroundInfo(0, 0, ""))
        } catch (_: Exception) { /* Foreground may be denied on some devices; continue. */ }

        val outcome = runCatching {
            BackupEngine.run(ctx, config) { progress ->
                runCatching {
                    setProgress(
                        workDataOf(
                            KEY_DONE to progress.done,
                            KEY_TOTAL to progress.total,
                            KEY_CURRENT to progress.current
                        )
                    )
                    setForeground(
                        makeForegroundInfo(progress.done, progress.total, progress.current)
                    )
                }
            }
        }

        config.lastRunMs = System.currentTimeMillis()
        if (outcome.isFailure) {
            val err = outcome.exceptionOrNull()
            val msg = err?.message ?: "Unknown error"
            config.lastResult = "Failed: $msg"
            if (err != null) AppInsights.trackException(err, mapOf("phase" to "engine"))
            else AppInsights.trackTrace("backup.failed: $msg", severity = 3)
            NotificationHelper.postFailure(ctx, msg)
            AppInsights.flushBlocking(1_500)
            return@withContext Result.retry()
        }

        val result = outcome.getOrThrow()
        config.lastResult =
            "Uploaded ${result.uploaded} · Skipped ${result.skipped} · Failed ${result.failed}"
        AppInsights.trackTrace("backup.completed", mapOf(
            "uploaded" to result.uploaded.toString(),
            "skipped" to result.skipped.toString(),
            "failed" to result.failed.toString()
        ))
        NotificationHelper.postSummary(ctx, result.uploaded, result.skipped, result.failed)
        AppInsights.flushBlocking(1_500)
        if (result.failed > 0 && result.uploaded == 0) Result.retry() else Result.success()
    }

    private fun makeForegroundInfo(done: Int, total: Int, current: String): ForegroundInfo {
        // Make sure the channel exists before any setForeground call posts a notification.
        NotificationHelper.ensureChannels(applicationContext)
        val notif = NotificationHelper.progressNotification(applicationContext, done, total, current)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NotificationHelper.ID_PROGRESS, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NotificationHelper.ID_PROGRESS, notif)
        }
    }

    companion object {
        const val UNIQUE_PERIODIC = "shay_backup_periodic"
        const val UNIQUE_ONESHOT  = "shay_backup_oneshot"

        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT = "current"

        fun schedulePeriodic(context: Context, config: ConfigStore) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(config.onlyWhenCharging)
                .build()
            val request = PeriodicWorkRequestBuilder<BackupWorker>(
                config.intervalHours.toLong().coerceAtLeast(1L),
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
        }

        fun runOnce(context: Context, config: ConfigStore) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (config.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .setRequiresCharging(config.onlyWhenCharging)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONESHOT, ExistingWorkPolicy.REPLACE, request
            )
        }

        fun cancelOneShot(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_ONESHOT)
        }

        fun observeOneShot(context: Context) =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(UNIQUE_ONESHOT)

        fun isRunning(infos: List<WorkInfo>?): Boolean =
            infos?.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } == true
    }
}
