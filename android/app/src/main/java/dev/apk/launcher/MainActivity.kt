package dev.apk.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.PowerManager
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import android.webkit.WebResourceRequest

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.setSupportMultipleWindows(false)
        web.settings.userAgentString =
            "${web.settings.userAgentString} APKLauncher/1.0"

        web.addJavascriptInterface(LauncherBridge(this), "ZenBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url
                if (url.scheme == "icon") {
                    return IconServer.respond(applicationContext, url.host)
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false
        }

        holdCpu()
        if (savedInstanceState == null) {
            web.loadUrl("file:///android_asset/panel/index.html")
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    private fun holdCpu() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "apk-launcher:cpu")
        lock.acquire()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        (web.parent as? android.view.ViewParent)?.let { (it as? android.view.ViewGroup)?.removeView(web) }
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val KEEP_EXTRA = "keep_pkgs"
    }
}