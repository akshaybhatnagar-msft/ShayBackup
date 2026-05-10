package com.shay.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Scans local MediaStore for images, videos, and downloads.
 * Each [MediaItem] knows its content URI, file name, size, and a stable dedupe key.
 */
object MediaScanner {

    enum class Category(val folderName: String) {
        IMAGES("photos"),
        VIDEOS("videos"),
        DOWNLOADS("downloads")
    }

    data class MediaItem(
        val category: Category,
        val id: Long,
        val uri: Uri,
        val fileName: String,
        val mimeType: String,
        val size: Long,
        val createdMs: Long,
        val modifiedMs: Long
    ) {
        /** Unique key for dedupe history. */
        val key: String get() = "${category.name}:$id:$modifiedMs"
    }

    fun scan(context: Context, config: ConfigStore): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        if (config.backupImages)    out += scanCollection(context, Category.IMAGES,    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, hasDateTaken = true)
        if (config.backupVideos)    out += scanCollection(context, Category.VIDEOS,    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,  hasDateTaken = true)
        if (config.backupDownloads) out += scanDownloads(context)
        return out
    }

    private fun scanCollection(
        context: Context,
        category: Category,
        base: Uri,
        hasDateTaken: Boolean
    ): List<MediaItem> {
        // DATE_TAKEN exists on Images & Video collections; not on Downloads.
        val projection = if (hasDateTaken) {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.DATE_TAKEN
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
        }
        val out = ArrayList<MediaItem>()
        context.contentResolver.query(base, projection, null, null, null)?.use { c ->
            val idIdx       = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx     = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx     = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIdx     = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val addedIdx    = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modifiedIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val takenIdx    = if (hasDateTaken) c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx) ?: "file_$id"
                val mime = c.getString(mimeIdx) ?: "application/octet-stream"
                val size = c.getLong(sizeIdx)
                val addedMs = c.getLong(addedIdx) * 1000L  // seconds → ms
                val modifiedMs = c.getLong(modifiedIdx) * 1000L
                val takenMs = if (takenIdx >= 0 && !c.isNull(takenIdx)) c.getLong(takenIdx) else 0L
                val createdMs = if (takenMs > 0L) takenMs else addedMs
                out += MediaItem(
                    category = category,
                    id = id,
                    uri = ContentUris.withAppendedId(base, id),
                    fileName = name,
                    mimeType = mime,
                    size = size,
                    createdMs = createdMs,
                    modifiedMs = modifiedMs
                )
            }
        }
        return out
    }

    private fun scanDownloads(context: Context): List<MediaItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        return scanCollection(context, Category.DOWNLOADS, MediaStore.Downloads.EXTERNAL_CONTENT_URI, hasDateTaken = false)
    }
}
