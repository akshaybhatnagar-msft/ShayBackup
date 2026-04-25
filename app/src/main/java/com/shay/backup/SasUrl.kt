package com.shay.backup

import java.net.URI

/**
 * A blob-container SAS URL of the form
 *   https://<account>.blob.core.windows.net/<container>[/...]?<sas>
 * decomposed into the parts the upload client needs.
 */
data class ParsedSasUrl(
    val accountUrl: String,
    val container: String,
    /** Includes the leading '?'. */
    val sasToken: String
) {
    fun rebuild(): String = "$accountUrl/$container$sasToken"
}

object SasUrl {

    /**
     * Parses a Blob SAS URL pasted from Azure Portal.
     * Returns null if [input] is not a valid `https://…/<container>?<sas>` URL.
     */
    fun parse(input: String): ParsedSasUrl? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val qIdx = trimmed.indexOf('?')
        if (qIdx <= 0 || qIdx == trimmed.length - 1) return null
        val pathPart = trimmed.substring(0, qIdx)
        val sasPart = trimmed.substring(qIdx)  // keep the leading '?'

        val uri = try { URI(pathPart) } catch (_: Exception) { return null }
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        if (scheme.lowercase() !in setOf("http", "https")) return null

        val accountUrl = buildString {
            append(scheme).append("://").append(host)
            if (uri.port > 0) append(":").append(uri.port)
        }

        val container = uri.path
            ?.split('/')
            ?.firstOrNull { it.isNotBlank() }
            ?: return null

        return ParsedSasUrl(accountUrl, container, sasPart)
    }
}
