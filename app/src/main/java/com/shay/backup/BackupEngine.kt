package com.shay.backup

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pulls items from [MediaScanner] and uploads each via [AzureBlobClient], skipping
 * anything already in [ConfigStore] history. Reports progress via [onProgress].
 */
object BackupEngine {

    data class Progress(val done: Int, val total: Int, val current: String)

    data class RunResult(val uploaded: Int, val skipped: Int, val failed: Int) {
        val attempted: Int get() = uploaded + failed
    }

    suspend fun run(
        context: Context,
        config: ConfigStore,
        onProgress: suspend (Progress) -> Unit = {}
    ): RunResult {
        require(config.isConfigured) { "Azure config is not complete" }

        val all = MediaScanner.scan(context, config)
        val pending = all.filter { !config.isBackedUp(it.key) }

        var uploaded = 0
        var failed = 0
        val skipped = all.size - pending.size
        val newKeys = ArrayList<String>()
        val newlyDoneFromFailed = ArrayList<String>()
        val newFailed = ArrayList<String>()

        pending.forEachIndexed { index, item ->
            onProgress(Progress(index, pending.size, item.fileName))
            val ok = uploadOne(context, config, item)
            if (ok) {
                uploaded++
                newKeys += item.key
                if (config.isFailed(item.key)) newlyDoneFromFailed += item.key
                if (newKeys.size >= 25) {
                    config.markBackedUp(newKeys)
                    config.clearFromFailed(newlyDoneFromFailed)
                    newKeys.clear()
                    newlyDoneFromFailed.clear()
                }
            } else {
                failed++
                newFailed += item.key
            }
        }
        config.markBackedUp(newKeys)
        config.clearFromFailed(newlyDoneFromFailed)
        config.markFailed(newFailed)
        onProgress(Progress(pending.size, pending.size, ""))
        return RunResult(uploaded = uploaded, skipped = skipped, failed = failed)
    }

    private fun uploadOne(
        context: Context,
        config: ConfigStore,
        item: MediaScanner.MediaItem
    ): Boolean {
        val blobName = blobNameFor(item)
        return try {
            val input = context.contentResolver.openInputStream(item.uri) ?: return false
            input.use {
                val code = AzureBlobClient.putBlob(
                    accountUrl = config.accountUrl,
                    container = config.container,
                    sas = config.sasToken,
                    blobName = blobName,
                    body = it,
                    contentLength = item.size,
                    contentType = item.mimeType
                )
                code in 200..299
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun blobNameFor(item: MediaScanner.MediaItem): String {
        val bucket = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(item.modifiedMs))
        return "${item.category.folderName}/$bucket/${item.fileName}"
    }
}
