package com.shay.backup

import android.net.Uri

enum class BackupStatus { DONE, PENDING, FAILED, UPLOADING }

/**
 * A scanned media item joined with its current backup status.
 * Computed in [BackupItemsRepository.collect].
 */
data class BackupItem(
    val key: String,
    val category: MediaScanner.Category,
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val modifiedMs: Long,
    val status: BackupStatus
)

data class BackupStats(
    val total: Int,
    val done: Int,
    val pending: Int,
    val failed: Int,
    val uploading: Int,
    val bytesDone: Long,
    val bytesPending: Long
) {
    val percentDone: Int get() = if (total == 0) 0 else (done * 100 / total)
}
