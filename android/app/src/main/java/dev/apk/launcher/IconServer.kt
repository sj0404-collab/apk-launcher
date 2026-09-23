package dev.apk.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object IconServer {

    fun respond(context: Context, pkg: String?): WebResourceResponse? {
        if (pkg.isNullOrBlank()) return null
        val bytes = runCatching {
            val ai = context.packageManager.getApplicationIcon(pkg)
            if (ai !is android.graphics.drawable.BitmapDrawable) return null
            val bmp = ai.bitmap
            if (bmp == null) return null
            val out = ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, out)
            out.toByteArray()
        }.getOrNull() ?: return null
        return WebResourceResponse(
            "image/png",
            "utf-8",
            200,
            "OK",
            mapOf("Cache-Control" to "max-age=3600"),
            ByteArrayInputStream(bytes),
        )
    }
}