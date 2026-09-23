package dev.apk.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class LauncherBridge(private val context: Context) {

    private val keeper by lazy { AppKeeper(context) }

    @JavascriptInterface
    fun listApps(): String {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        val apps = JSONArray()
        for (ri in resolved) {
            val pkg = ri.activityInfo.packageName
            if (pkg == context.packageName) continue
            if (!seen.add(pkg)) continue
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                .getOrDefault(pkg)
            val version = runCatching {
                pm.getPackageInfo(pkg, 0).versionName ?: ""
            }.getOrDefault("")
            apps.put(
                JSONObject()
                    .put("packageName", pkg)
                    .put("label", label)
                    .put("versionName", version)
                    .put("launchable", true)
            )
        }
        return JSONObject().put("apps", apps).toString()
    }

    @JavascriptInterface
    fun launchApp(pkg: String): Boolean {
        return try {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launch == null) return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    @JavascriptInterface
    fun keepApp(pkg: String, keep: Boolean): Boolean {
        return keeper.set(pkg, keep)
    }

    @JavascriptInterface
    fun getKeeps(): String {
        val arr = JSONArray()
        keeper.list().forEach { arr.put(it) }
        return JSONObject().put("keeps", arr).toString()
    }

    @JavascriptInterface
    fun listProcesses(): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = am.runningAppProcesses ?: emptyList()
        val arr = JSONArray()
        for (p in running) {
            if (p.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) continue
            arr.put(
                JSONObject()
                    .put("packageName", p.processName)
                    .put("pid", p.pid)
                    .put("importance", importanceName(p.importance))
            )
        }
        return JSONObject().put("processes", arr).toString()
    }

    @JavascriptInterface
    fun appIconAvailable(pkg: String): Boolean {
        return try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            ai.icon != 0 || ai.logo != 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun importanceName(importance: Int): String = when {
        importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "background"
        importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground-service"
        importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        else -> "foreground"
    }
}