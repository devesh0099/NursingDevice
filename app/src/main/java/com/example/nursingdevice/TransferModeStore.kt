package com.example.nursingdevice

import android.content.Context

object TransferModeStore {
    private const val PREFS = "transfer_mode"
    private const val KEY_WIFI_DIRECT = "wifi_direct_enabled"

    fun isWifiDirect(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WIFI_DIRECT, false)

    fun setWifiDirect(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WIFI_DIRECT, enabled)
            .apply()
    }
}
