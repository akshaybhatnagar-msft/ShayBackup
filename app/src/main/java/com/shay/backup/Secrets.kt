package com.shay.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android-Keystore-backed encrypted prefs for material that should not sit in
 * plain SharedPreferences (storage account key, future tokens). Falls back to
 * plain prefs on devices where the keystore init fails so we never crash on
 * boot — the fallback is logged but not user-facing.
 */
object Secrets {

    private const val FILE = "shay_backup_secrets"
    private const val K_ACCOUNT_KEY = "azure_account_key"

    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val ctx = context.applicationContext
            val sp = try {
                val masterKey = MasterKey.Builder(ctx)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    ctx,
                    FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // Last-resort fallback so the app still launches.
                ctx.getSharedPreferences("${FILE}_fallback", Context.MODE_PRIVATE)
            }
            prefs = sp
            return sp
        }
    }

    fun getAccountKey(context: Context): String =
        prefs(context).getString(K_ACCOUNT_KEY, "").orEmpty()

    fun setAccountKey(context: Context, value: String) {
        prefs(context).edit().putString(K_ACCOUNT_KEY, value.trim()).apply()
    }
}
