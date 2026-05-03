package com.siddarthkay.syncup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import gobridge.MobileAPI
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

// Broadcast intent surface for automation tools (Tasker, MacroDroid, Llama,
// etc.). Mirrors the shape syncthing-android exposes so existing user
// recipes work with minimal changes.
//
// Actions (replace prefix with applicationId):
//   com.siddarthkay.syncup.action.START
//   com.siddarthkay.syncup.action.STOP
//   com.siddarthkay.syncup.action.RESCAN
//     extras (optional): folder (String) — folder ID to rescan; empty/missing
//                        means rescan all folders.
//
// The receiver is exported so external apps can fire it. There is no
// per-action permission gate — matching syncthing-android's default. If
// future versions need to gate this, wrap the dispatch in a SharedPreferences
// check.
class AppConfigReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppConfigReceiver"
        private val executor = Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // Default-OFF gate. Without this the receiver would honor broadcasts
        // from any installed app on the device. The user opts in from
        // Settings → Allow external control. Decision logged loudly so the
        // user can audit if something unexpected fires.
        if (!SyncthingPrefs.getExternalControlEnabled(context)) {
            Log.w(TAG, "rejected $action: external control disabled in settings")
            return
        }
        Log.i(TAG, "received $action (caller=${intent.`package` ?: "unknown"})")
        when (action) {
            "com.siddarthkay.syncup.action.START" -> {
                SyncthingService.start(context)
            }
            "com.siddarthkay.syncup.action.STOP" -> {
                SyncthingService.stop(context)
            }
            "com.siddarthkay.syncup.action.RESCAN" -> {
                val folderId = intent.getStringExtra("folder")?.takeIf { it.isNotBlank() }
                // Hit the local REST API on a background thread; the daemon
                // must already be running for this to do anything useful.
                executor.execute { rescan(folderId) }
            }
            else -> Log.w(TAG, "unknown action $action")
        }
    }

    private fun rescan(folderId: String?) {
        val api = try {
            MobileAPI()
        } catch (e: Throwable) {
            Log.w(TAG, "MobileAPI init failed", e)
            return
        }
        val key = api.getAPIKey() ?: return
        val gui = api.getGUIAddress() ?: return
        if (key.isEmpty() || gui.isEmpty()) {
            Log.w(TAG, "daemon not ready, skipping rescan")
            return
        }
        val urlStr = if (folderId != null) {
            "http://$gui/rest/db/scan?folder=${URLEncoder.encode(folderId, "UTF-8")}"
        } else {
            "http://$gui/rest/db/scan"
        }
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-API-Key", key)
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.doOutput = true
            // Empty body — Syncthing rescan POST does not need one.
            OutputStreamWriter(conn.outputStream).use { it.write("") }
            val code = conn.responseCode
            if (code >= 400) {
                Log.w(TAG, "rescan returned $code")
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "rescan failed", e)
        }
    }
}
