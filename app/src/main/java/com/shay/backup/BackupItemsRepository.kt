package com.shay.backup

import android.content.Context

object BackupItemsRepository {

    /**
     * Scans MediaStore once and joins each row against [ConfigStore] history.
     * The optional [currentlyUploadingKey] is used to mark exactly one item as UPLOADING.
     */
    fun collect(
        context: Context,
        config: ConfigStore,
        currentlyUploadingKey: String? = null
    ): List<BackupItem> {
        val media = MediaScanner.scan(context, config)
        val uploaded = config.uploadedKeys()
        val failed = config.failedKeys()
        return media.map { m ->
            val status = when {
                m.key == currentlyUploadingKey -> BackupStatus.UPLOADING
                uploaded.contains(m.key)       -> BackupStatus.DONE
                failed.contains(m.key)         -> BackupStatus.FAILED
                else                           -> BackupStatus.PENDING
            }
            BackupItem(
                key = m.key,
                category = m.category,
                uri = m.uri,
                fileName = m.fileName,
                mimeType = m.mimeType,
                size = m.size,
                modifiedMs = m.modifiedMs,
                status = status
            )
        }
    }

    fun statsOf(items: List<BackupItem>): BackupStats {
        var done = 0; var failed = 0; var uploading = 0
        var bytesDone = 0L; var bytesPending = 0L
        for (i in items) {
            when (i.status) {
                BackupStatus.DONE -> { done++; bytesDone += i.size }
                BackupStatus.FAILED -> { failed++; bytesPending += i.size }
                BackupStatus.UPLOADING -> { uploading++; bytesPending += i.size }
                BackupStatus.PENDING -> { bytesPending += i.size }
            }
        }
        val pending = items.size - done - failed - uploading
        return BackupStats(
            total = items.size,
            done = done,
            pending = pending,
            failed = failed,
            uploading = uploading,
            bytesDone = bytesDone,
            bytesPending = bytesPending
        )
    }
}
