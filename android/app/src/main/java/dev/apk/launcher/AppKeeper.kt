package dev.apk.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build

class AppKeeper(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("keeps", Context.MODE_PRIVATE)

    fun set(pkg: String, keep: Boolean): Boolean {
        if (keep) {
            prefs.edit().putBoolean(sanitize(pkg), true).apply()
        } else {
            prefs.edit().remove(sanitize(pkg)).apply()
        }
        syncService()
        return true
    }

    fun list(): List<String> =
        prefs.all.filterValues { it == true }.map { it.key }

    fun isKept(pkg: String): Boolean = prefs.getBoolean(sanitize(pkg), false)

    fun syncService() {
        val intent = Intent(context, KeepAliveService::class.java).apply {
            putStringArrayListExtra(MainActivity.KEEP_EXTRA, ArrayList(list()))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop() {
        context.stopService(Intent(context, KeepAliveService::class.java))
        prefs.edit().clear().apply()
    }

    private fun sanitize(pkg: String): String = pkg.trim()
}