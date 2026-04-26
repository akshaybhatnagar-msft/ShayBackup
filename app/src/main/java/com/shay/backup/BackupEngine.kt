package com.shay.backup

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

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

        // After a fresh install or history reset, rebuild dedupe state from the cloud
        // so we don't re-upload everything we already have.
        if (config.uploadedKeys().isEmpty()) {
            try { reconcileFromCloud(context, config) } catch (_: Exception) { /* best effort */ }
        }

        val all = MediaScanner.scan(context, config)
        val pending = all.filter { !config.isBackedUp(it.key) }

        var uploaded = 0
        var failed = 0
        val skipped = all.size - pending.size
        val newKeys = ArrayList<String>()
        val newlyDoneFromFailed = ArrayList<String>()
        val newFailed = ArrayList<String>()

        pending.forEachIndexed runLoop@{ index, item ->
            // Cooperative cancellation: if WorkManager cancels us (Stop button,
            // network-constraint loss, etc.), stop between items and persist what we have.
            if (!currentCoroutineContext().isActive) return@runLoop
            // Mid-run network handover guard — stop if we drop off Wi-Fi while wifi-only is on.
            if (config.wifiOnly && !NetworkUtils.isOnWifi(context)) return@runLoop
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
            val input = context.contentResolver.openInputStream(item.uri)
            if (input == null) {
                AppInsights.trackTrace("upload.openInputStream.null", mapOf(
                    "category" to item.category.name
                ), severity = 2)
                return false
            }
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
                val ok = code in 200..299
                if (!ok) AppInsights.trackTrace("upload.httpFailed", mapOf(
                    "status" to code.toString(),
                    "category" to item.category.name
                ), severity = 2)
                ok
            }
        } catch (e: Exception) {
            AppInsights.trackException(e, mapOf(
                "phase" to "uploadOne",
                "category" to item.category.name
            ))
            false
        }
    }

    private fun blobNameFor(item: MediaScanner.MediaItem): String =
        blobName(item.category, item.fileName, item.modifiedMs)

    /** Same naming used at upload time, exposed for link sharing. */
    fun blobName(category: MediaScanner.Category, fileName: String, modifiedMs: Long): String {
        val bucket = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(modifiedMs))
        return "${category.folderName}/$bucket/$fileName"
    }

    /**
     * Lists blobs in the configured container and marks every local file whose
     * derived blob name is already present as "backed up". Returns the count
     * matched. Useful after an uninstall+reinstall to avoid re-uploading.
     */
    fun reconcileFromCloud(context: Context, config: ConfigStore): Int {
        if (!config.isConfigured) return 0
        val remote = AzureBlobClient.listBlobs(
            config.accountUrl, config.container, config.sasToken
        ).toHashSet()
        if (remote.isEmpty()) return 0
        val local = MediaScanner.scan(context, config)
        val matched = ArrayList<String>()
        for (item in local) {
            val name = blobName(item.category, item.fileName, item.modifiedMs)
            if (remote.contains(name)) matched += item.key
        }
        config.markBackedUp(matched)
        config.clearFromFailed(matched)
        return matched.size
    }
}
