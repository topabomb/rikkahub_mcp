package net.weero.measix.pilot.utils

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

private const val TAG = "UsageStatsUtil"

/**
 * Whether the app has been granted the "Usage access" special permission
 * (android.permission.PACKAGE_USAGE_STATS), required to query screen usage time.
 */
fun Context.hasUsageStatsPermission(): Boolean {
    val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Open the system "Usage access" settings page so the user can grant the
 * PACKAGE_USAGE_STATS permission manually.
 */
fun Context.openUsageAccessSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.onFailure {
        Log.e(TAG, "openUsageAccessSettings failed", it)
    }
}
