package com.shay.backup

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Account-level admin REST calls (create/delete/list containers, server-side
 * copy blob). All operations authenticate by appending an Account SAS minted
 * locally with the account key — no Shared-Key signing needed.
 */
object AzureAdmin {

    private const val API_VERSION = "2020-12-06"

    data class ContainerSummary(val name: String, val lastModified: String?)

    fun createContainer(accountUrl: String, container: String, accountSasQs: String): Int {
        val url = URL("${accountUrl.trimEnd('/')}/$container?restype=container&${strip(accountSasQs)}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = false
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("x-ms-version", API_VERSION)
            setRequestProperty("Content-Length", "0")
            setFixedLengthStreamingMode(0L)
        }
        return try { conn.responseCode } finally { conn.disconnect() }
    }

    fun deleteContainer(accountUrl: String, container: String, accountSasQs: String): Int {
        val url = URL("${accountUrl.trimEnd('/')}/$container?restype=container&${strip(accountSasQs)}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("x-ms-version", API_VERSION)
        }
        return try { conn.responseCode } finally { conn.disconnect() }
    }

    /** Server-side Copy Blob within the same account — bytes never traverse the device. */
    fun copyBlob(
        accountUrl: String,
        destContainer: String,
        destBlobName: String,
        sourceContainer: String,
        sourceBlobName: String,
        accountSasQs: String,
        cacheControl: String? = null
    ): Int {
        val accountBase = accountUrl.trimEnd('/')
        val sasNoQ = strip(accountSasQs)
        val destPath = "$destContainer/${encodePath(destBlobName)}"
        val sourceUrl = "$accountBase/$sourceContainer/${encodePath(sourceBlobName)}?$sasNoQ"
        val url = URL("$accountBase/$destPath?$sasNoQ")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = false
            connectTimeout = 30_000
            readTimeout = 5 * 60_000
            setRequestProperty("x-ms-version", API_VERSION)
            setRequestProperty("x-ms-copy-source", sourceUrl)
            setRequestProperty("Content-Length", "0")
            cacheControl?.let { setRequestProperty("x-ms-blob-cache-control", it) }
            setFixedLengthStreamingMode(0L)
        }
        return try { conn.responseCode } finally { conn.disconnect() }
    }

    fun listShareContainers(accountUrl: String, accountSasQs: String): List<ContainerSummary> {
        val out = ArrayList<ContainerSummary>()
        var marker: String? = null
        val base = accountUrl.trimEnd('/')
        val sasNoQ = strip(accountSasQs)
        while (true) {
            val markerPart = marker?.let { "&marker=" + URLEncoder.encode(it, "UTF-8") } ?: ""
            val url = URL("$base/?comp=list&prefix=share-&$sasNoQ$markerPart")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("x-ms-version", API_VERSION)
            }
            try {
                if (conn.responseCode !in 200..299) return out
                val (rows, next) = parseContainersResponse(conn.inputStream)
                out += rows
                marker = next
                if (marker.isNullOrBlank()) return out
            } finally {
                conn.disconnect()
            }
        }
    }

    /** mid-level helper used by ShareBundleBuilder to upload the gallery HTML / thumbs. */
    fun putBlobBytes(
        accountUrl: String,
        container: String,
        blobName: String,
        accountSasQs: String,
        body: ByteArray,
        contentType: String,
        cacheControl: String? = null
    ): Int {
        val url = URL(
            "${accountUrl.trimEnd('/')}/$container/${encodePath(blobName)}?${strip(accountSasQs)}"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 5 * 60_000
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("x-ms-blob-type", "BlockBlob")
            setRequestProperty("x-ms-version", API_VERSION)
            setRequestProperty("Content-Type", contentType)
            cacheControl?.let { setRequestProperty("x-ms-blob-cache-control", it) }
        }
        return try {
            conn.outputStream.use { it.write(body) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun parseContainersResponse(input: InputStream): Pair<List<ContainerSummary>, String?> {
        val parser = Xml.newPullParser()
        parser.setInput(input, "UTF-8")
        val rows = ArrayList<ContainerSummary>()
        var nextMarker: String? = null
        var inContainer = false
        var name: String? = null
        var lastModified: String? = null
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "Container" -> { inContainer = true; name = null; lastModified = null }
                    "Name" -> if (inContainer) name = parser.nextText()
                    "Last-Modified" -> if (inContainer) lastModified = parser.nextText()
                    "NextMarker" -> nextMarker = parser.nextText().takeIf { it.isNotBlank() }
                }
                XmlPullParser.END_TAG -> if (parser.name == "Container") {
                    name?.let { rows += ContainerSummary(it, lastModified) }
                    inContainer = false
                }
            }
            event = parser.next()
        }
        return rows to nextMarker
    }

    private fun strip(qs: String): String = if (qs.startsWith("?")) qs.removePrefix("?") else qs

    private fun encodePath(s: String): String =
        s.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
}
