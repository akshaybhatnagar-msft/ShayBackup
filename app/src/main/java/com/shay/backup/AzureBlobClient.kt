package com.shay.backup

import android.util.Xml
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.xmlpull.v1.XmlPullParser

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

    fun blobUrl(
        accountUrl: String,
        container: String,
        blobName: String,
        sas: String
    ): String = buildBlobUrl(accountUrl, container, blobName, sas)

    /**
     * Enumerates blob names in [container] using the List Blobs REST API.
     * SAS must include `l` (list) permission. Pages internally up to [maxBlobs]
     * names. Returns whatever it has on partial failure.
     */
    fun listBlobs(
        accountUrl: String,
        container: String,
        sas: String,
        maxBlobs: Int = 100_000
    ): List<String> {
        val out = ArrayList<String>()
        val sasFragment = if (sas.startsWith("?")) sas.removePrefix("?") else sas
        var marker: String? = null
        val base = "${accountUrl.trimEnd('/')}/$container"
        while (true) {
            val markerPart = marker?.let { "&marker=" + URLEncoder.encode(it, "UTF-8") } ?: ""
            val url = URL("$base?restype=container&comp=list&$sasFragment$markerPart")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("x-ms-version", API_VERSION)
            }
            try {
                if (conn.responseCode !in 200..299) return out
                val (names, next) = parseListResponse(conn.inputStream)
                out += names
                marker = next
                if (out.size >= maxBlobs || marker.isNullOrBlank()) return out
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parseListResponse(input: InputStream): Pair<List<String>, String?> {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val names = ArrayList<String>()
        var nextMarker: String? = null
        var inBlob = false
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Blob" -> inBlob = true
                    "Name" -> if (inBlob) names += parser.nextText()
                    "NextMarker" -> nextMarker = parser.nextText().takeIf { it.isNotBlank() }
                }
                XmlPullParser.END_TAG -> if (parser.name == "Blob") inBlob = false
            }
            event = parser.next()
        }
        return names to nextMarker
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
