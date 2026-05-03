package com.shay.backup

import android.content.Context
import android.content.SharedPreferences

/**
 * Plain SharedPreferences-backed config + dedupe state.
 *
 * Storage of the SAS is unencrypted on device — acceptable when using a scoped, short-lived
 * container SAS. For production, swap in EncryptedSharedPreferences.
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("shay_backup", Context.MODE_PRIVATE)

    // ── Azure target ────────────────────────────────────────────────────────

    var accountUrl: String
        get() = prefs.getString(K_ACCOUNT_URL, "").orEmpty().trim()
        set(value) { prefs.edit().putString(K_ACCOUNT_URL, value.trim()).apply() }

    var container: String
        get() = prefs.getString(K_CONTAINER, "").orEmpty().trim()
        set(value) { prefs.edit().putString(K_CONTAINER, value.trim()).apply() }

    /** Stored as-is, including the leading "?" if the user pasted one. */
    var sasToken: String
        get() = prefs.getString(K_SAS, "").orEmpty().trim()
        set(value) { prefs.edit().putString(K_SAS, value.trim()).apply() }

    val isConfigured: Boolean
        get() = accountUrl.isNotBlank() && container.isNotBlank() && sasToken.isNotBlank()

    // ── Sources ─────────────────────────────────────────────────────────────

    var backupImages: Boolean
        get() = prefs.getBoolean(K_SRC_IMAGES, true)
        set(value) { prefs.edit().putBoolean(K_SRC_IMAGES, value).apply() }

    var backupVideos: Boolean
        get() = prefs.getBoolean(K_SRC_VIDEOS, true)
        set(value) { prefs.edit().putBoolean(K_SRC_VIDEOS, value).apply() }

    var backupDownloads: Boolean
        get() = prefs.getBoolean(K_SRC_DOWNLOADS, true)
        set(value) { prefs.edit().putBoolean(K_SRC_DOWNLOADS, value).apply() }

    // ── Schedule ────────────────────────────────────────────────────────────

    var wifiOnly: Boolean
        get() = prefs.getBoolean(K_WIFI_ONLY, true)
        set(value) { prefs.edit().putBoolean(K_WIFI_ONLY, value).apply() }

    var onlyWhenCharging: Boolean
        get() = prefs.getBoolean(K_CHARGING_ONLY, false)
        set(value) { prefs.edit().putBoolean(K_CHARGING_ONLY, value).apply() }

    /** Hours between automatic runs. WorkManager minimum is 15 minutes; we cap at 1+ hours. */
    var intervalHours: Int
        get() = prefs.getInt(K_INTERVAL_HOURS, 6)
        set(value) { prefs.edit().putInt(K_INTERVAL_HOURS, value.coerceAtLeast(1)).apply() }

    var schedulingEnabled: Boolean
        get() = prefs.getBoolean(K_SCHED_ENABLED, true)
        set(value) { prefs.edit().putBoolean(K_SCHED_ENABLED, value).apply() }

    // ── Last run ────────────────────────────────────────────────────────────

    var lastRunMs: Long
        get() = prefs.getLong(K_LAST_RUN_MS, 0L)
        set(value) { prefs.edit().putLong(K_LAST_RUN_MS, value).apply() }

    var lastResult: String
        get() = prefs.getString(K_LAST_RESULT, "").orEmpty()
        set(value) { prefs.edit().putString(K_LAST_RESULT, value).apply() }

    /** Whether we've already shown the SAS-scope warning when sharing a link. */
    var shareScopeWarned: Boolean
        get() = prefs.getBoolean(K_SHARE_WARNED, false)
        set(value) { prefs.edit().putBoolean(K_SHARE_WARNED, value).apply() }

    /** "list" or "tile" — persisted across launches. Default tile. */
    var viewMode: String
        get() = prefs.getString(K_VIEW_MODE, "tile").orEmpty().ifBlank { "tile" }
        set(value) { prefs.edit().putString(K_VIEW_MODE, value).apply() }

    // ── Dedupe history ──────────────────────────────────────────────────────

    fun isBackedUp(key: String): Boolean = prefs.getStringSet(K_HISTORY, emptySet())!!.contains(key)
    fun isFailed(key: String): Boolean = prefs.getStringSet(K_FAILED, emptySet())!!.contains(key)

    fun uploadedKeys(): Set<String> = prefs.getStringSet(K_HISTORY, emptySet())!!.toSet()
    fun failedKeys(): Set<String> = prefs.getStringSet(K_FAILED, emptySet())!!.toSet()

    fun markBackedUp(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val current = prefs.getStringSet(K_HISTORY, emptySet())!!.toMutableSet()
        current.addAll(keys)
        prefs.edit().putStringSet(K_HISTORY, current).apply()
    }

    fun markFailed(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val current = prefs.getStringSet(K_FAILED, emptySet())!!.toMutableSet()
        current.addAll(keys)
        prefs.edit().putStringSet(K_FAILED, current).apply()
    }

    fun clearFromFailed(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val current = prefs.getStringSet(K_FAILED, emptySet())!!.toMutableSet()
        if (current.removeAll(keys.toSet())) {
            prefs.edit().putStringSet(K_FAILED, current).apply()
        }
    }

    fun clearHistory() {
        prefs.edit().remove(K_HISTORY).remove(K_FAILED).apply()
    }

    companion object {
        private const val K_ACCOUNT_URL = "azure_account_url"
        private const val K_CONTAINER = "azure_container"
        private const val K_SAS = "azure_sas"

        private const val K_SRC_IMAGES = "src_images"
        private const val K_SRC_VIDEOS = "src_videos"
        private const val K_SRC_DOWNLOADS = "src_downloads"

        private const val K_WIFI_ONLY = "wifi_only"
        private const val K_CHARGING_ONLY = "charging_only"
        private const val K_INTERVAL_HOURS = "interval_hours"
        private const val K_SCHED_ENABLED = "sched_enabled"

        private const val K_LAST_RUN_MS = "last_run_ms"
        private const val K_LAST_RESULT = "last_result"

        private const val K_HISTORY = "uploaded_keys"
        private const val K_FAILED = "failed_keys"
        private const val K_SHARE_WARNED = "share_scope_warned"
        private const val K_VIEW_MODE = "view_mode"
    }
}
