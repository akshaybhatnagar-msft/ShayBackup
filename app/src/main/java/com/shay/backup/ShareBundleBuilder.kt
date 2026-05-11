package com.shay.backup

import android.content.Context
import java.util.UUID

/**
 * Orchestrates the full "share these photos" flow:
 *   1. mint short-lived account SAS using the encrypted account key
 *   2. create a fresh share-{id} container
 *   3. generate a small JPEG thumbnail per item ON the device and upload to thumbs/
 *   4. server-side Copy Blob each selected source blob into the container (no device bandwidth)
 *   5. mint a read-only container SAS for recipients (default 7-day expiry)
 *   6. render the gallery HTML (thumb in <img>, original via download / lightbox)
 *   7. return the shareable URL
 *
 * Recipient-facing gallery loads ~600 KB total (12 thumbnails) instead of ~120 MB.
 */
object ShareBundleBuilder {

    private const val DEFAULT_SHARE_DAYS = 7
    private const val CACHE_CONTROL = "public, max-age=2592000"  // 30 days

    data class ShareResult(
        val shareId: String,
        val containerName: String,
        val galleryUrl: String,
        val expiryMs: Long
    )

    fun share(
        context: Context,
        config: ConfigStore,
        items: List<BackupItem>,
        expiryDays: Int = DEFAULT_SHARE_DAYS
    ): ShareResult {
        check(config.canSignSas) {
            "Account key not set — paste the storage connection string in Settings → Sharing."
        }
        require(items.isNotEmpty()) { "Nothing selected to share" }

        val now = System.currentTimeMillis()
        val recipientExpiryMs = now + expiryDays * 24L * 60 * 60 * 1000L

        // 1. Account SAS — covers all admin operations during this share.
        val accountSas = SasSigner.accountSas(
            accountName = config.accountName,
            accountKeyBase64 = config.accountKey,
            permissions = "rwdlc",
            services = "b",
            resourceTypes = "sco",
            startMs = now - 5 * 60_000L,
            expiryMs = now + 30 * 60_000L
        )

        val shareId = UUID.randomUUID().toString().take(8)
        val shareContainer = "share-$shareId"

        // 2. Create the new container.
        val createCode = AzureAdmin.createContainer(config.accountUrl, shareContainer, accountSas)
        if (createCode !in 200..299) error("Create container failed (HTTP $createCode)")

        // 3 & 4. For each item: generate+upload thumb, then server-side copy original.
        val entries = ArrayList<GalleryHtml.Entry>()
        for (item in items) {
            val srcBlobName = BackupEngine.blobName(item.category, item.fileName, item.modifiedMs)
            val destBlobName = item.fileName
            val isVideo = item.category == MediaScanner.Category.VIDEOS
            val thumbBlobName = "thumbs/${stripExt(destBlobName)}.jpg"

            // Thumbnail (best-effort; if generation fails, the tile falls back to a glyph)
            val thumbBytes = ThumbnailGenerator.generate(context, item.uri, isVideo)
            val thumbUploaded = if (thumbBytes != null) {
                val code = AzureAdmin.putBlobBytes(
                    accountUrl = config.accountUrl,
                    container = shareContainer,
                    blobName = thumbBlobName,
                    accountSasQs = accountSas,
                    body = thumbBytes,
                    contentType = "image/jpeg",
                    cacheControl = CACHE_CONTROL
                )
                code in 200..299
            } else false

            // Full-res original via server-side copy.
            val copyCode = AzureAdmin.copyBlob(
                accountUrl = config.accountUrl,
                destContainer = shareContainer,
                destBlobName = destBlobName,
                sourceContainer = config.container,
                sourceBlobName = srcBlobName,
                accountSasQs = accountSas,
                cacheControl = CACHE_CONTROL
            )
            if (copyCode !in 200..299) error("Copy blob failed for ${item.fileName} (HTTP $copyCode)")

            entries += GalleryHtml.Entry(
                displayName = item.fileName,
                blobName = destBlobName,
                thumbBlobName = if (thumbUploaded) thumbBlobName else null,
                sizeBytes = item.size,
                mimeType = item.mimeType
            )
        }

        // 5. Recipient SAS — read-only, no list, scoped to the share container only.
        val recipientSas = SasSigner.containerReadSas(
            accountName = config.accountName,
            accountKeyBase64 = config.accountKey,
            containerName = shareContainer,
            startMs = now - 5 * 60_000L,
            expiryMs = recipientExpiryMs,
            includeList = false
        )

        // 6. Render and upload the gallery HTML.
        val html = GalleryHtml.render(
            context = context,
            shareId = shareId,
            entries = entries,
            readSasQs = recipientSas,
            accountUrl = config.accountUrl,
            shareContainer = shareContainer,
            expiryMs = recipientExpiryMs
        )
        val htmlCode = AzureAdmin.putBlobBytes(
            accountUrl = config.accountUrl,
            container = shareContainer,
            blobName = "index.html",
            accountSasQs = accountSas,
            body = html.toByteArray(Charsets.UTF_8),
            contentType = "text/html; charset=utf-8"
        )
        if (htmlCode !in 200..299) error("Upload index.html failed (HTTP $htmlCode)")

        val galleryUrl =
            "${config.accountUrl.trimEnd('/')}/$shareContainer/index.html$recipientSas"

        return ShareResult(
            shareId = shareId,
            containerName = shareContainer,
            galleryUrl = galleryUrl,
            expiryMs = recipientExpiryMs
        )
    }

    private fun stripExt(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }
}
