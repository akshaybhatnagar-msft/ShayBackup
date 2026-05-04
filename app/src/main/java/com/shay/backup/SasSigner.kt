package com.shay.backup

import android.util.Base64
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Builds Azure Storage **service SAS** tokens locally using the storage
 * account key. Implements the Service SAS canonical-string format from
 * https://learn.microsoft.com/en-us/rest/api/storageservices/create-service-sas.
 *
 * Output is a query string starting with "?" ready to append to a blob/container URL.
 * Container scope: signedResource = "c"; blob scope: signedResource = "b".
 */
object SasSigner {

    /**
     * 2020-12-06 = first version that includes signedEncryptionScope in the
     * service SAS and account SAS canonical StringToSign. Our signing code
     * generates that format, so `sv` must match.
     */
    private const val API_VERSION = "2020-12-06"

    /** Read-only (optionally list) container SAS valid from [startMs] to [expiryMs]. */
    fun containerReadSas(
        accountName: String,
        accountKeyBase64: String,
        containerName: String,
        startMs: Long,
        expiryMs: Long,
        includeList: Boolean = false
    ): String {
        val perms = if (includeList) "rl" else "r"
        return buildSas(
            accountName = accountName,
            accountKeyBase64 = accountKeyBase64,
            canonicalResource = "/blob/$accountName/$containerName",
            signedResource = "c",
            permissions = perms,
            startMs = startMs,
            expiryMs = expiryMs
        )
    }

    /**
     * Account-level SAS. Signs operations across all containers in the account.
     * Used to create/delete share containers and to do server-side Copy Blob.
     */
    fun accountSas(
        accountName: String,
        accountKeyBase64: String,
        permissions: String,        // e.g. "rwdlc"  read/write/delete/list/create
        services: String,           // "b" for blob
        resourceTypes: String,      // any of "s" (service), "c" (container), "o" (object)
        startMs: Long,
        expiryMs: Long
    ): String {
        val st = isoTime(startMs)
        val se = isoTime(expiryMs)
        val stringToSign = listOf(
            accountName,
            permissions,
            services,
            resourceTypes,
            st,
            se,
            "",                 // signedIP
            "https",            // signedProtocol
            API_VERSION,
            ""                  // signedEncryptionScope — required since 2020-12-06
        ).joinToString("\n") + "\n"  // trailing newline required by spec
        val sig = sign(stringToSign, accountKeyBase64)
        return buildString {
            append("?sv=").append(API_VERSION)
            append("&ss=").append(services)
            append("&srt=").append(resourceTypes)
            append("&sp=").append(permissions)
            append("&st=").append(urlEncode(st))
            append("&se=").append(urlEncode(se))
            append("&spr=https")
            append("&sig=").append(urlEncode(sig))
        }
    }

    /** Read-only single-blob SAS valid from [startMs] to [expiryMs]. */
    fun blobReadSas(
        accountName: String,
        accountKeyBase64: String,
        containerName: String,
        blobName: String,
        startMs: Long,
        expiryMs: Long
    ): String = buildSas(
        accountName = accountName,
        accountKeyBase64 = accountKeyBase64,
        canonicalResource = "/blob/$accountName/$containerName/$blobName",
        signedResource = "b",
        permissions = "r",
        startMs = startMs,
        expiryMs = expiryMs
    )

    private fun buildSas(
        accountName: String,
        accountKeyBase64: String,
        canonicalResource: String,
        signedResource: String,
        permissions: String,
        startMs: Long,
        expiryMs: Long
    ): String {
        val st = isoTime(startMs)
        val se = isoTime(expiryMs)

        // Canonical string-to-sign for service SAS, API 2020-04-08:
        // signedPermissions, signedStart, signedExpiry, canonicalizedResource,
        // signedIdentifier, signedIP, signedProtocol, signedVersion, signedResource,
        // signedSnapshotTime, signedEncryptionScope, rscc, rscd, rsce, rscf, rsct
        val stringToSign = listOf(
            permissions,
            st,
            se,
            canonicalResource,
            "",                    // signedIdentifier
            "",                    // signedIP
            "https",               // signedProtocol
            API_VERSION,
            signedResource,
            "",                    // signedSnapshotTime
            "",                    // signedEncryptionScope
            "", "", "", "", ""     // rscc, rscd, rsce, rscf, rsct
        ).joinToString("\n")

        val sig = sign(stringToSign, accountKeyBase64)

        return buildString {
            append("?sv=").append(API_VERSION)
            append("&st=").append(urlEncode(st))
            append("&se=").append(urlEncode(se))
            append("&sr=").append(signedResource)
            append("&sp=").append(permissions)
            append("&spr=https")
            append("&sig=").append(urlEncode(sig))
        }
    }

    private fun isoTime(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(ms))
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun sign(message: String, base64Key: String): String {
        val keyBytes = Base64.decode(base64Key, Base64.DEFAULT)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        val sig = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sig, Base64.NO_WRAP)
    }
}
