package dev.apk.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.apk.launcher.BuildConfig.UPDATER_REPO
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Updater {

    private const val TAG = "APKUpdater"
    private val GITHUB_API = "https://api.github.com/repos/$UPDATER_REPO/releases/latest"
    private var busy = false

    fun check(context: Context, currentVersion: String, onResult: (latest: String, url: String) -> Unit) {
        if (busy) return
        busy = true
        Thread {
            try {
                val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "apk-launcher")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val code = conn.responseCode
                if (code != 200) return@Thread
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "").removePrefix("v")
                val assets = json.optJSONArray("assets") ?: JSONObject.NULL
                var apkUrl = ""
                if (assets is org.json.JSONArray) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (apkUrl.isEmpty()) {
                    apkUrl = "https://github.com/$UPDATER_REPO/releases"
                }
                if (tag.isNotEmpty() && compareVersions(tag, currentVersion) > 0) {
                    Handler(Looper.getMainLooper()).post { onResult(tag, apkUrl) }
                }
            } catch (e: Exception) {
                Log.d(TAG, "update check failed: ${e.message}")
            } finally {
                busy = false
            }
        }.start()
    }

    fun compareVersions(a: String, b: String): Int {
        val av = a.split('.').mapNotNull { part -> part.filter { it.isDigit() }.toIntOrNull() }
        val bv = b.split('.').mapNotNull { part -> part.filter { it.isDigit() }.toIntOrNull() }
        val n = maxOf(av.size, bv.size)
        for (i in 0 until n) {
            val x = av.getOrElse(i) { 0 }
            val y = bv.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}