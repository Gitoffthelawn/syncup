package com.siddarthkay.syncup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

// Mirrors the JS-side AsyncStorage vault registry into SharedPreferences so
// the foreground service notification can render "N vaults stale" without
// going through the JS bridge.
//
// Threshold mirrors VAULT_STALE_THRESHOLD_MS in
// mobile-app/src/utils/vaultRegistry.ts. If you change one, change the
// other.
object VaultRegistry {
    private const val TAG = "VaultRegistry"
    private const val PREFS_NAME = "syncup_vault_registry"
    private const val KEY_PAYLOAD = "payload_v1"

    const val STALE_THRESHOLD_MS: Long = 60L * 60L * 1000L

    data class Snapshot(
        val vaultIds: Set<String>,
        val lastSyncs: Map<String, Long>,
    )

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun setPayload(ctx: Context, json: String) {
        prefs(ctx).edit().putString(KEY_PAYLOAD, json).apply()
    }

    fun load(ctx: Context): Snapshot {
        val raw = prefs(ctx).getString(KEY_PAYLOAD, null) ?: return Snapshot(emptySet(), emptyMap())
        return parse(raw)
    }

    fun staleCount(ctx: Context, nowMs: Long = System.currentTimeMillis()): Int {
        val snap = load(ctx)
        if (snap.vaultIds.isEmpty()) return 0
        var stale = 0
        for (id in snap.vaultIds) {
            val ts = snap.lastSyncs[id] ?: continue
            if (nowMs - ts > STALE_THRESHOLD_MS) stale++
        }
        return stale
    }

    private fun parse(raw: String): Snapshot {
        return try {
            val obj = JSONObject(raw)
            val vaultsArr = obj.optJSONArray("vaults")
            val ids = HashSet<String>()
            if (vaultsArr != null) {
                for (i in 0 until vaultsArr.length()) {
                    val s = vaultsArr.optString(i, "")
                    if (s.isNotEmpty()) ids.add(s)
                }
            }
            val lastSyncs = HashMap<String, Long>()
            val syncsObj = obj.optJSONObject("lastSyncs")
            if (syncsObj != null) {
                val keys = syncsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = syncsObj.optLong(k, 0L)
                    if (v > 0L) lastSyncs[k] = v
                }
            }
            Snapshot(ids, lastSyncs)
        } catch (e: JSONException) {
            Log.w(TAG, "parse failed", e)
            Snapshot(emptySet(), emptyMap())
        }
    }
}
