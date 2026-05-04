package com.shay.backup

import android.content.Context
import java.util.UUID

/**
 * Orchestrates the full "share these photos" flow:
 *   1. mint short-lived account SAS using the encrypted account key
 *   2. create a fresh share-{id} container
 *   3. server-side Copy Blob each selected source blob into it
 *   4. mint a read-only container SAS for recipients (default 7-day expiry)
 *   5. render the gallery HTML and upload it as index.html
 *   6. return the shareable URL
 *
 * Total wire activity: 1 + N + 1 PUTs (no payload bytes leave the device for the photos).
 */
object ShareBundleBuilder {

    private const val DEFAULT_SHARE_DAYS = 7

    data class ShareResult(
        val shareId: String,
        val containerName: String,
        val galleryUrl: String,
        val expiryMs: Long
    )

    /**
     * Builds and uploads the share bundle. Throws [IllegalStateException] when no
     * account key is configured, or [RuntimeException] on Azure errors with the
     * HTTP status surfaced for diagnosis.
     */
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
            permissions = "rwdlc",   // read/write/delete/list/create
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

        // 3. Copy selected source blobs into the share container.
        val entries = ArrayList<GalleryHtml.Entry>()
        for (item in items) {
            val srcBlobName = BackupEngine.blobName(item.category, item.fileName, item.modifiedMs)
            val destBlobName = item.fileName
            val copyCode = AzureAdmin.copyBlob(
                accountUrl = config.accountUrl,
                destContainer = shareContainer,
                destBlobName = destBlobName,
                sourceContainer = config.container,
                sourceBlobName = srcBlobName,
                accountSasQs = accountSas
            )
            if (copyCode !in 200..299) error("Copy blob failed for ${item.fileName} (HTTP $copyCode)")
            entries += GalleryHtml.Entry(
                displayName = item.fileName,
                blobName = destBlobName,
                sizeBytes = item.size,
                mimeType = item.mimeType
            )
        }

        // 4. Recipient SAS — read-only, no list, scoped to the share container only.
        val recipientSas = SasSigner.containerReadSas(
            accountName = config.accountName,
            accountKeyBase64 = config.accountKey,
            containerName = shareContainer,
            startMs = now - 5 * 60_000L,
            expiryMs = recipientExpiryMs,
            includeList = false
        )

        // 5. Render and upload the gallery HTML.
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

        // 6. Public URL using the recipient's read-only SAS.
        val galleryUrl =
            "${config.accountUrl.trimEnd('/')}/$shareContainer/index.html$recipientSas"

        return ShareResult(
            shareId = shareId,
            containerName = shareContainer,
            galleryUrl = galleryUrl,
            expiryMs = recipientExpiryMs
        )
    }
}
