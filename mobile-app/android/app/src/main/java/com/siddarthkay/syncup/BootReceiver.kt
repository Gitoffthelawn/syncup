package com.siddarthkay.syncup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Starts SyncthingService on device boot when the user has opted in via
// Settings → Start at boot. Default-OFF; nothing happens until the user
// flips the toggle. BOOT_COMPLETED is a protected broadcast so only the
// system can fire it, despite android:exported="true" being required.
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SyncthingPrefs.getStartOnBoot(context)) {
            Log.i(TAG, "boot ignored: start-on-boot disabled")
            return
        }
        Log.i(TAG, "boot received, starting SyncthingService")
        SyncthingService.start(context)
    }
}
