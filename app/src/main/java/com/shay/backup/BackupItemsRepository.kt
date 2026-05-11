package com.shay.backup

import android.content.Context

object BackupItemsRepository {

    /**
     * Scans MediaStore once and joins each row against [ConfigStore] history.
     * The optional [currentlyUploadingKey] is used to mark exactly one item as UPLOADING.
     * Sorted newest-first based on [ConfigStore.sortMode].
     */
    fun collect(
        context: Context,
        config: ConfigStore,
        currentlyUploadingKey: String? = null
    ): List<BackupItem> {
        val raw = MediaScanner.scan(context, config)
        val sorted = when (config.sortMode) {
            "modified" -> raw.sortedByDescending { it.modifiedMs }
            else       -> raw.sortedByDescending { it.createdMs }
        }
        val uploaded = config.uploadedKeys()
        val failed = config.failedKeys()
        return sorted.map { m ->
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
                createdMs = m.createdMs,
                modifiedMs = m.modifiedMs,
                durationMs = m.durationMs,
                status = status
            )
        }
    }

    fun statsOf(items: List<BackupItem>): BackupStats {
        var done = 0; var failed = 0; var uploading = 0
        var bytesDone = 0L; var bytesPending = 0L
        for (i in items) {
            when (i.status) {
                BackupStatus.DONE      -> { done++; bytesDone += i.size }
                BackupStatus.FAILED    -> { failed++; bytesPending += i.size }
                BackupStatus.UPLOADING -> { uploading++; bytesPending += i.size }
                BackupStatus.PENDING   -> { bytesPending += i.size }
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
