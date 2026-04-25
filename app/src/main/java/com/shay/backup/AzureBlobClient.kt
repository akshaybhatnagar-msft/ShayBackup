package com.shay.backup

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Azure Blob REST client that uses a SAS token for auth.
 * Single Put Blob (BlockBlob) — supports up to 5000 MiB per request when the
 * x-ms-version is at least 2019-12-12.
 */
object AzureBlobClient {

    private const val API_VERSION = "2020-04-08"

    /**
     * Uploads [body] to {accountUrl}/{container}/{blobName}{sas}. Returns the HTTP status code.
     * Throws on connection-level failure.
     */
    fun putBlob(
        accountUrl: String,
        container: String,
        sas: String,
        blobName: String,
        body: InputStream,
        contentLength: Long,
        contentType: String
    ): Int {
        val url = URL(buildBlobUrl(accountUrl, container, blobName, sas))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            useCaches = false
            connectTimeout = 30_000
            readTimeout = 10 * 60_000
            if (contentLength >= 0L) setFixedLengthStreamingMode(contentLength)
            else setChunkedStreamingMode(64 * 1024)
            setRequestProperty("x-ms-blob-type", "BlockBlob")
            setRequestProperty("x-ms-version", API_VERSION)
            setRequestProperty("Content-Type", contentType)
        }
        try {
            conn.outputStream.use { out -> body.copyTo(out, bufferSize = 64 * 1024) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    /** A small ping that uploads a 0-byte blob named `.shay-backup-ping` to verify auth. */
    fun ping(accountUrl: String, container: String, sas: String): Int {
        val empty = java.io.ByteArrayInputStream(ByteArray(0))
        return putBlob(
            accountUrl = accountUrl,
            container = container,
            sas = sas,
            blobName = ".shay-backup-ping",
            body = empty,
            contentLength = 0L,
            contentType = "application/octet-stream"
        )
    }

    private fun buildBlobUrl(
        accountUrl: String,
        container: String,
        blobName: String,
        sas: String
    ): String {
        val base = accountUrl.trimEnd('/')
        val encodedBlobPath = blobName.split('/')
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val sasFragment = if (sas.startsWith("?")) sas else "?$sas"
        return "$base/$container/$encodedBlobPath$sasFragment"
    }
}
