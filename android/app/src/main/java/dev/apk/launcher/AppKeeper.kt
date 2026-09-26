package dev.apk.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock

class AppKeeper(private val context: Context) {

    val prefs: SharedPreferences =
        context.getSharedPreferences("keeps", Context.MODE_PRIVATE)

    private val pinPrefs: SharedPreferences =
        context.getSharedPreferences("pins", Context.MODE_PRIVATE)

    init {
        pruneMissingPackages()
    }

    fun set(pkg: String, keep: Boolean): Boolean {
        val clean = normalizePackage(pkg)
        if (clean.isEmpty()) return false
        val e = prefs.edit()
        if (keep) e.putBoolean(clean, true) else e.remove(clean)
        e.apply()
        if (!keep) pinPrefs.edit().remove(clean).apply()
        syncService(if (keep) clean else null)
        return true
    }

    fun setPin(pkg: String, pin: Boolean): Boolean {
        val clean = normalizePackage(pkg)
        if (clean.isEmpty()) return false
        val e = pinPrefs.edit()
        if (pin) e.putBoolean(clean, true) else e.remove(clean)
        e.apply()
        if (pin) prefs.edit().putBoolean(clean, true).apply()
        syncService(if (pin) clean else null)
        return true
    }

    fun list(): List<String> {
        pruneMissingPackages()
        return prefs.all.filterValues { it == true }.keys.sorted()
    }

    fun isKept(pkg: String): Boolean {
        pruneMissingPackages()
        return prefs.getBoolean(normalizePackage(pkg), false)
    }

    fun pinnedList(): List<String> {
        pruneMissingPackages()
        return pinPrefs.all.filterValues { it == true }.keys.sorted()
    }

    fun isPinned(pkg: String): Boolean {
        pruneMissingPackages()
        return pinPrefs.getBoolean(normalizePackage(pkg), false)
    }

    fun syncService(reactivate: String? = null) {
        val list = list()
        val pins = pinnedList()
        val intent = Intent(context, KeepAliveService::class.java).apply {
            putStringArrayListExtra(MainActivity.KEEP_EXTRA, ArrayList(list))
            putStringArrayListExtra(MainActivity.PIN_EXTRA, ArrayList(pins))
            if (reactivate != null) putExtra(KeepAliveService.EXTRA_REACTIVATE, reactivate)
        }
        if (list.isEmpty() && pins.isEmpty() && !KeepAliveService.hasPersistedOverlays(context)) {
            context.stopService(intent)
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    fun stop() {
        context.stopService(Intent(context, KeepAliveService::class.java))
        prefs.edit().clear().apply()
        pinPrefs.edit().clear().apply()
    }

    private fun pruneMissingPackages() {
        if (prefs.all.isEmpty() && pinPrefs.all.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPruneAt in 0 until PRUNE_INTERVAL_MS) return
        lastPruneAt = now

        val keepKeys = prefs.all.keys.mapNotNull { it as? String }
        val invalidKeeps = keepKeys.filterNot(::isInstalled)
        if (invalidKeeps.isNotEmpty()) {
            prefs.edit().apply { invalidKeeps.forEach(::remove) }.apply()
        }

        val pinKeys = pinPrefs.all.keys.mapNotNull { it as? String }
        val invalidPins = pinKeys.filterNot(::isInstalled)
        if (invalidPins.isNotEmpty()) {
            pinPrefs.edit().apply { invalidPins.forEach(::remove) }.apply()
        }

        val pinsWithoutKeep = pinKeys.filter { !prefs.getBoolean(it, false) }
        if (pinsWithoutKeep.isNotEmpty()) {
            prefs.edit().apply {
                pinsWithoutKeep.forEach { putBoolean(it, true) }
            }.apply()
        }
    }

    private fun normalizePackage(pkg: String): String = pkg.trim().substringBefore(':')

    private companion object {
        const val PRUNE_INTERVAL_MS = 60_000L
        var lastPruneAt = Long.MIN_VALUE / 2
    }

    private fun isInstalled(pkg: String): Boolean {
        if (runCatching {
                context.packageManager.getApplicationInfo(pkg, 0)
                true
            }.getOrDefault(false)
        ) {
            return true
        }
        if (runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }
                .getOrDefault(false)
        ) {
            return true
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return runCatching {
            am.runningAppProcesses?.any {
                it.processName == pkg || it.processName.startsWith("$pkg:")
            } ?: false
        }.getOrDefault(false)
    }
}
