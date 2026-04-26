package com.shay.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtils {

    /**
     * True iff the *active* network is Wi-Fi (transport-level check).
     *
     * Stricter than WorkManager's `NetworkType.UNMETERED` — some carriers report
     * cellular as "not metered" (e.g. some 5G unlimited plans), which lets
     * UNMETERED-constrained work run on cellular. We avoid that by checking
     * the transport directly.
     */
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
