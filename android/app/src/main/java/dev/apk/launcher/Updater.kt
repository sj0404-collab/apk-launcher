package dev.apk.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.apk.launcher.BuildConfig.UPDATER_REPO
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

object Updater {

    private const val TAG = "APKUpdater"
    private const val RETRY_DELAY_MS = 500L
    private val GITHUB_API = "https://api.github.com/repos/$UPDATER_REPO/releases/latest"
    private val busy = AtomicBoolean(false)

    fun check(
        context: Context,
        currentVersion: String,
        onResult: (latest: String, url: String) -> Unit,
    ) {
        if (context is android.app.Activity &&
            (context.isFinishing || context.isDestroyed)
        ) {
            return
        }
        if (!busy.compareAndSet(false, true)) {
            Handler(Looper.getMainLooper()).postDelayed(
                { check(context, currentVersion, onResult) },
                RETRY_DELAY_MS,
            )
            return
        }
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(GITHUB_API).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "apk-launcher")
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@Thread

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v")
                val assets = json.optJSONArray("assets") ?: return@Thread
                val apkUrl = findApkUrl(assets)
                if (tag.isNotEmpty() && apkUrl.isNotEmpty() && compareVersions(tag, currentVersion) > 0) {
                    Handler(Looper.getMainLooper()).post {
                        if (context is android.app.Activity &&
                            (context.isFinishing || context.isDestroyed)
                        ) {
                            return@post
                        }
                        onResult(tag, apkUrl)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "update check failed: ${e.message}")
            } finally {
                connection?.disconnect()
                busy.set(false)
            }
        }.start()
    }

    fun compareVersions(a: String, b: String): Int {
        val av = versionParts(a)
        val bv = versionParts(b)
        val n = maxOf(av.size, bv.size)
        for (i in 0 until n) {
            val x = av.getOrElse(i) { 0 }
            val y = bv.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        val ap = prerelease(a)
        val bp = prerelease(b)
        return when {
            ap.isEmpty() && bp.isEmpty() -> 0
            ap.isEmpty() -> 1
            bp.isEmpty() -> -1
            else -> comparePrerelease(ap, bp)
        }
    }

    private fun findApkUrl(assets: JSONArray): String {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk", ignoreCase = true) && isTrustedApkUrl(url)) {
                return url
            }
        }
        return ""
    }

    private fun isTrustedApkUrl(value: String): Boolean {
        val url = runCatching { URL(value) }.getOrNull() ?: return false
        val host = url.host?.lowercase() ?: return false
        return url.protocol.equals("https", ignoreCase = true) &&
            (host == "github.com" ||
                host == "githubusercontent.com" ||
                host.endsWith(".github.com") ||
                host.endsWith(".githubusercontent.com"))
    }

    private fun comparePrerelease(a: String, b: String): Int {
        val av = a.split('.')
        val bv = b.split('.')
        val n = maxOf(av.size, bv.size)
        for (i in 0 until n) {
            val x = av.getOrNull(i) ?: return -1
            val y = bv.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val compared = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (compared != 0) return compared
        }
        return 0
    }

    private fun prerelease(value: String): String {
        val normalized = value.trim().removePrefix("v")
        val dash = normalized.indexOf('-')
        return if (dash < 0) "" else normalized.substring(dash + 1).substringBefore('+')
    }

    private fun versionParts(value: String): List<Int> {
        val version = value.trim().removePrefix("v")
            .substringBefore('-')
            .substringBefore('+')
        return version.split('.').mapNotNull { part ->
            Regex("\\d+").find(part)?.value?.toIntOrNull()
        }
    }
}
