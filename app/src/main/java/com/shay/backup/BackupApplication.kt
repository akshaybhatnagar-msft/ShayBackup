package com.shay.backup

import android.app.Application

class BackupApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        // Schedule periodic work if user has previously enabled it.
        if (ConfigStore(this).schedulingEnabled) {
            BackupWorker.schedulePeriodic(this, ConfigStore(this))
        }
    }
}
