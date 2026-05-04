package com.shay.backup

/**
 * Parser for Azure Storage connection strings:
 *   `DefaultEndpointsProtocol=https;AccountName=acct;AccountKey={base64};EndpointSuffix=core.windows.net`
 *
 * Returns null when required fields are missing, so the Settings UI can show
 * an inline error.
 */
data class StorageCredentials(
    val accountName: String,
    val accountKey: String,
    val protocol: String = "https",
    val endpointSuffix: String = "core.windows.net"
) {
    val blobEndpoint: String get() = "$protocol://$accountName.blob.$endpointSuffix"
}

object StorageConnectionString {
    fun parse(input: String): StorageCredentials? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val map = trimmed.split(';')
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq <= 0) null else it.substring(0, eq).trim() to it.substring(eq + 1).trim()
            }
            .toMap()
        val name = map["AccountName"]?.takeIf { it.isNotBlank() } ?: return null
        val key = map["AccountKey"]?.takeIf { it.isNotBlank() } ?: return null
        return StorageCredentials(
            accountName = name,
            accountKey = key,
            protocol = map["DefaultEndpointsProtocol"]?.takeIf { it.isNotBlank() } ?: "https",
            endpointSuffix = map["EndpointSuffix"]?.takeIf { it.isNotBlank() } ?: "core.windows.net"
        )
    }
}
