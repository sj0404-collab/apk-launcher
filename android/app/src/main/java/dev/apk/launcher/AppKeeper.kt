package dev.apk.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build

class AppKeeper(private val context: Context) {

    val prefs: SharedPreferences =
        context.getSharedPreferences("keeps", Context.MODE_PRIVATE)

    fun set(pkg: String, keep: Boolean): Boolean {
        val clean = pkg.trim()
        if (clean.isEmpty()) return false
        val e = prefs.edit()
        if (keep) e.putBoolean(clean, true) else e.remove(clean)
        e.apply()
        syncService()
        return true
    }

    fun list(): List<String> =
        prefs.all.filterValues { it == true }.keys.sorted()

    fun isKept(pkg: String): Boolean = prefs.getBoolean(pkg.trim(), false)

    fun syncService() {
        val list = list()
        val intent = Intent(context, KeepAliveService::class.java).apply {
            putStringArrayListExtra(MainActivity.KEEP_EXTRA, ArrayList(list))
        }
        if (list.isEmpty()) {
            context.stopService(intent)
            return
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
}