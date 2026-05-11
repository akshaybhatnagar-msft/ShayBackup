package com.shay.backup

import android.net.Uri

enum class BackupStatus { DONE, PENDING, FAILED, UPLOADING }

data class BackupItem(
    val key: String,
    val category: MediaScanner.Category,
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    /** Best-effort creation time (DATE_TAKEN if present, else DATE_ADDED). */
    val createdMs: Long,
    /** File-system / metadata last-modified time (DATE_MODIFIED). */
    val modifiedMs: Long,
    /** Video duration in ms; 0 for non-video items. */
    val durationMs: Long,
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
