package com.siddarthkay.syncup

import android.content.Context
import android.content.SharedPreferences

// one SharedPreferences file shared between the service and the turbo module.
object SyncthingPrefs {
    private const val PREFS_NAME = "syncthing.prefs"
    private const val KEY_WIFI_ONLY_SYNC = "wifi_only_sync"
    private const val KEY_CHARGING_ONLY_SYNC = "charging_only_sync"
    private const val KEY_ALLOW_METERED_WIFI = "allow_metered_wifi"
    private const val KEY_ALLOW_MOBILE_DATA = "allow_mobile_data"
    private const val KEY_EXTERNAL_CONTROL = "external_control_enabled"
    private const val KEY_START_ON_BOOT = "start_on_boot_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getWifiOnlySync(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_ONLY_SYNC, false)

    fun setWifiOnlySync(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY_SYNC, value).apply()
    }

    fun getChargingOnlySync(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHARGING_ONLY_SYNC, false)

    fun setChargingOnlySync(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHARGING_ONLY_SYNC, value).apply()
    }

    fun getAllowMeteredWifi(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_METERED_WIFI, false)

    fun setAllowMeteredWifi(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_METERED_WIFI, value).apply()
    }

    fun getAllowMobileData(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ALLOW_MOBILE_DATA, false)

    fun setAllowMobileData(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALLOW_MOBILE_DATA, value).apply()
    }

    // Default OFF. Once enabled, ANY app on the device can fire
    // START/STOP/RESCAN broadcasts at AppConfigReceiver — there is no
    // per-caller authorization. Treat this toggle as informed-consent
    // rather than a strong security boundary.
    fun getExternalControlEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXTERNAL_CONTROL, false)

    fun setExternalControlEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXTERNAL_CONTROL, value).apply()
    }

    // Default OFF. Read by BootReceiver on ACTION_BOOT_COMPLETED to decide
    // whether to launch SyncthingService unattended.
    fun getStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_START_ON_BOOT, false)

    fun setStartOnBoot(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_START_ON_BOOT, value).apply()
    }

    private val backupKeys = listOf(
        KEY_WIFI_ONLY_SYNC,
        KEY_CHARGING_ONLY_SYNC,
        KEY_ALLOW_METERED_WIFI,
        KEY_ALLOW_MOBILE_DATA,
        KEY_EXTERNAL_CONTROL,
        KEY_START_ON_BOOT,
    )

    fun exportAsJson(context: Context): String {
        val p = prefs(context)
        val obj = org.json.JSONObject()
        for (key in backupKeys) {
            obj.put(key, p.getBoolean(key, false))
        }
        return obj.toString()
    }

    fun importFromJson(context: Context, json: String): Boolean {
        val obj = org.json.JSONObject(json)
        val editor = prefs(context).edit()
        var applied = 0
        for (key in backupKeys) {
            if (obj.has(key)) {
                editor.putBoolean(key, obj.optBoolean(key, false))
                applied++
            }
        }
        editor.apply()
        return applied > 0
    }
}
