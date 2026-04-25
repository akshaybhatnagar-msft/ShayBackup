package com.shay.backup

import android.app.Application

class BackupApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppInsights.init(this)
        installCrashHandler()
        AppInsights.trackTrace("app.started")
        NotificationHelper.ensureChannels(this)
        if (ConfigStore(this).schedulingEnabled) {
            BackupWorker.schedulePeriodic(this, ConfigStore(this))
        }
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AppInsights.trackException(throwable, mapOf("thread" to thread.name))
                AppInsights.flushBlocking(2_000)
            } catch (_: Throwable) { /* never fail in a crash handler */ }
            prev?.uncaughtException(thread, throwable)
        }
    }
}
