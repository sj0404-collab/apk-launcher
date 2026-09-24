package dev.apk.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val kept = HashSet<String>()
    private val pinned = HashSet<String>()
    private val reluctant = HashSet<String>()
    private val forceRelaunchTime = HashMap<String, Long>()
    private val lastAlive = HashMap<String, Long>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = true

    private val overlays = HashMap<String, OverlayController>()
    private val lastBounds = HashMap<String, Rect>()
    private val positionPreset = HashMap<String, Int>()

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            ensureKeptProcesses()
            handler.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "apk-launcher:keep-watchdog",
        ).apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> intent.getStringExtra(EXTRA_PKG)?.let { showOverlay(it) }
            ACTION_HIDE_OVERLAY -> intent.getStringExtra(EXTRA_PKG)?.let { hideOverlay(it) }
        }
        val list = intent?.getStringArrayListExtra(MainActivity.KEEP_EXTRA)
        val pins = intent?.getStringArrayListExtra(MainActivity.PIN_EXTRA)
        if (list != null) {
            kept.clear()
            kept.addAll(list)
            pinned.clear()
            pinned.addAll(pins ?: emptyList())
            // Списки «нелюбимых» и тайминги привязываем к актуальному составу keep.
            reluctant.retainAll(kept)
            forceRelaunchTime.keys.retainAll(kept)
            lastAlive.keys.retainAll(kept)
        } else if (kept.isEmpty()) {
            // Система перезапустила процесс (START_STICKY / null intent) —
            // восстанавливаем списки «держимых» и «мини-окон» из хранилища.
            val keeper = AppKeeper(this)
            kept.addAll(keeper.list())
            pinned.addAll(keeper.pinnedList())
        }
        startForeground(NOTIF_ID, buildNotification())
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        if (kept.isEmpty() && overlays.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun ensureKeptProcesses() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val current = am.runningAppProcesses ?: emptyList()

        // Пока лаунчер в фокусе — не поднимаем приложения поверх него,
        // иначе невозможно выбрать другое приложение.
        val launcherFocused = current.any {
            it.processName == packageName &&
                it.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        val now = SystemClock.elapsedRealtime()
        for (pkg in kept) {
            if (pkg in reluctant) continue
            val alive = current.any { it.processName == pkg || it.processName.startsWith("$pkg:") }
            val lastAliveAt = lastAlive[pkg] ?: 0L
            val lastForced = forceRelaunchTime[pkg] ?: 0L
            if (alive) {
                lastAlive[pkg] = now
                if (pkg in pinned && !overlays.containsKey(pkg)) showOverlay(pkg)
                if (lastForced != 0L && lastAliveAt != 0L && now - lastForced > RELAUNCH_MIN_GAP_MS) {
                    // Пожил подольше — доверие вернулось, авто-рестарты активны.
                    reluctant.remove(pkg)
                }
                continue
            }
            hideOverlay(pkg)
            if (now - lastAliveAt < RELAUNCH_MIN_GAP_MS) {
                // Только что был жив — его закрыли руками (свайп/стоп).
                // Никаких мгновенных «воскрешений» на глазах у пользователя.
                if (lastForced != 0L) reluctant.add(pkg)
                continue
            }
            if (now - lastForced < RELAUNCH_MIN_GAP_MS) {
                // Наш подъём тут же снова снесён — стоп авто-перезапускам.
                reluctant.add(pkg)
                continue
            }
            if (launcherFocused) continue
            relaunch(pkg)
            forceRelaunchTime[pkg] = now
        }

        if (kept.isEmpty() && overlays.isEmpty()) {
            handler.removeCallbacks(watchdog)
            stopSelf()
        }
    }

    private fun relaunch(pkg: String) {
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                if (pkg in pinned) {
                    // «Пинованные» поднимаем сразу в интерактивном мини-окне
                    // (весь экран приложения в уменьшенном виде, без потери фокуса).
                    startActivity(launch, freeformOptions(lastBounds[pkg] ?: miniBounds()))
                    showOverlay(pkg)
                } else {
                    launch.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                    startActivity(launch)
                }
            }
        }
    }

    private fun freeformOptions(bounds: Rect? = null): android.os.Bundle {
        val r = bounds ?: miniBounds()
        val opts = android.app.ActivityOptions.makeBasic().apply {
            setLaunchBounds(r)
            runCatching {
                android.app.ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(this, WINDOWING_MODE_FREEFORM)
            }
        }
        return opts.toBundle()
    }

    private fun miniBounds(): Rect {
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.62f).toInt()
        val h = (dm.heightPixels * 0.6f).toInt()
        val x = dm.widthPixels - w
        val y = dm.heightPixels - h
        return Rect(x, y, x + w, y + h)
    }

    // ================= Оверлей кнопок мини-окна =================

    fun showOverlay(pkg: String) {
        if (overlays.containsKey(pkg)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                )
            )
            return
        }
        val bounds = lastBounds[pkg] ?: miniBounds().also { lastBounds[pkg] = it }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(6), dp(3), dp(6))
            background = Gradient.drawRound(0xcc101420.toInt(), dp(14))
            addView(ovBtn("\u25A3") { fullWindow(pkg) })   // ▣ во весь экран
            addView(ovBtn("+") { scaleWindow(pkg, 1.2f) })   // увеличить
            addView(ovBtn("\u2212") { scaleWindow(pkg, 0.82f) }) // сузить
            addView(ovBtn("\u25C7") { moveWindow(pkg) })   // ◇ сменить позицию
            addView(ovBtn("\u2715") { closeWindow(pkg) })  // ✕ закрыть
        }
        val onTopType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            onTopType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = bounds.top + dp(12)
        }
        runCatching { wm.addView(bar, params) }.onFailure { return }
        overlays[pkg] = OverlayController(bar, params)
    }

    fun hideOverlay(pkg: String) {
        val controller = overlays.remove(pkg) ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(controller.bar)
        }
    }

    private fun ovBtn(glyph: String, action: () -> Unit): TextView {
        val s = dp(16).toFloat()
        return TextView(this).apply {
            text = glyph
            setTextColor(0xFFF2F5FA.toInt())
            textSize = s
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumWidth = dp(36)
            minimumHeight = dp(36)
            setOnClickListener { action() }
            if (glyph == "\u2715") {
                background = Gradient.drawRound(0xCC33111F.toInt(), dp(10))
                setTextColor(0xFFFF8080.toInt())
            }
        }
    }

    private fun closeWindow(pkg: String) {
        hideOverlay(pkg)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        runCatching { am.killBackgroundProcesses(pkg) }
        handler.postDelayed({
            val still = runCatching {
                am.runningAppProcesses?.any {
                    it.processName == pkg || it.processName.startsWith("$pkg:")
                } ?: false
            }.getOrDefault(false)
            if (still) toast("Приложение осталось — свайпните его вверх")
        }, 600)
    }

    private fun scaleWindow(pkg: String, factor: Float) {
        val cur = lastBounds[pkg] ?: miniBounds().also { lastBounds[pkg] = it }
        val dm = resources.displayMetrics
        val cw = cur.width()
        val ch = cur.height()
        var w = (cw * factor).toInt()
        var h = (ch * factor).toInt()
        w = w.coerceIn((dm.widthPixels * 0.26f).toInt(), (dm.widthPixels * 0.96f).toInt())
        h = h.coerceIn((dm.heightPixels * 0.26f).toInt(), (dm.heightPixels * 0.96f).toInt())
        val cx = cur.centerX()
        val cy = cur.centerY()
        val r = Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        applyBounds(pkg, r)
    }

    private fun fullWindow(pkg: String) {
        val dm = resources.displayMetrics
        applyBounds(pkg, Rect(0, 0, dm.widthPixels, dm.heightPixels))
    }

    private fun moveWindow(pkg: String) {
        val cur = lastBounds[pkg] ?: miniBounds().also { lastBounds[pkg] = it }
        val w = cur.width()
        val h = cur.height()
        val dm = resources.displayMetrics
        val dw = dm.widthPixels
        val dh = dm.heightPixels
        val idx = ((positionPreset[pkg] ?: -1) + 1) % ANCHORS.size
        positionPreset[pkg] = idx
        val (grav, ax, ay) = ANCHORS[idx]
        val r = when (grav) {
            Gravity.BOTTOM or Gravity.START -> Rect(ax, dh - h - ay, w + ax, dh - ay)
            Gravity.BOTTOM or Gravity.END -> Rect(dw - w - ax, dh - h - ay, dw - ax, dh - ay)
            Gravity.TOP or Gravity.END -> Rect(dw - w - ax, ay, dw - ax, h + ay)
            Gravity.CENTER or Gravity.END -> Rect(dw - w - ax, (dh - h) / 2, dw - ax, (dh + h) / 2)
            Gravity.CENTER -> Rect((dw - w) / 2, (dh - h) / 2, (dw + w) / 2, (dh + h) / 2)
            else -> Rect(ax, ay, w + ax, h + ay) // TOP or START
        }
        applyBounds(pkg, r)
    }

    private fun applyBounds(pkg: String, r: Rect) {
        lastBounds[pkg] = r
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                // Если окно уже открыто — задача выводится вперёд с новыми
                // размерами; если нет — запускается заново в мини-окне.
                startActivity(launch, freeformOptions(r))
            }
        }
        repositionBar(pkg, r)
    }

    private fun repositionBar(pkg: String, r: Rect) {
        val controller = overlays[pkg] ?: return
        controller.params.apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = r.top + dp(12)
        }
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(controller.bar, controller.params)
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Держатель процессов",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            Notification.Builder(this)

        val summary = kept.take(3).joinToString(", ") { pkg ->
            pkg.substringAfterLast('.').ifBlank { pkg }
        }
        val text = if (kept.isEmpty()) "Список пуст"
        else "Держу живыми: $summary"

        return builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("APK Launcher")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(watchdog)
        for (pkg in overlays.keys.toList()) hideOverlay(pkg)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class OverlayController(
        val bar: View,
        val params: WindowManager.LayoutParams,
    )

    private object Gradient {
        fun drawRound(color: Int, radius: Int): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = radius.toFloat()
            }
    }

    companion object {
        const val ACTION_SHOW_OVERLAY = "dev.apk.launcher.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "dev.apk.launcher.action.HIDE_OVERLAY"
        const val EXTRA_PKG = "pkg"
        private const val CHANNEL_ID = "keep-alive"
        private const val NOTIF_ID = 7
        private const val WATCH_INTERVAL_MS = 2000L
        private const val RELAUNCH_MIN_GAP_MS = 15000L
        private const val WINDOWING_MODE_FREEFORM = 5
        private val ANCHORS = listOf(
            Triple(Gravity.BOTTOM or Gravity.END, 14, 14),
            Triple(Gravity.BOTTOM or Gravity.START, 14, 14),
            Triple(Gravity.TOP or Gravity.END, 14, 14),
            Triple(Gravity.TOP or Gravity.START, 14, 14),
            Triple(Gravity.CENTER or Gravity.END, 14, 0),
            Triple(Gravity.CENTER, 0, 0),
        )
    }
}