package io.github.wongnd.jamkkan

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

object Perms {

    fun hasUsage(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun hasOverlay(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun hasNotify(c: Context): Boolean {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return false
        return nm.areNotificationsEnabled()
    }

    fun open(a: Activity, kind: String) {
        val pkgUri = Uri.parse("package:" + a.packageName)
        when (kind) {
            "usage" -> {
                // 일부 기기는 앱별 화면으로 바로 가고, 안 되면 목록 화면으로
                if (!tryStart(a, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri))) {
                    tryStart(a, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            "overlay" -> {
                if (!tryStart(a, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri))) {
                    tryStart(a, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            }
            "notify" -> {
                val prefs = a.getSharedPreferences("perms", Context.MODE_PRIVATE)
                val asked = prefs.getBoolean("askedNotify", false)
                if (Build.VERSION.SDK_INT >= 33 && !asked &&
                    a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    prefs.edit().putBoolean("askedNotify", true).apply()
                    a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), MainActivity.REQ_NOTIFY)
                    return
                }
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
                tryStart(a, i)
            }
        }
    }

    private fun tryStart(a: Activity, i: Intent): Boolean = try {
        a.startActivity(i)
        true
    } catch (_: Exception) {
        false
    }
}
