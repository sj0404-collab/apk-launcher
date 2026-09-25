package dev.apk.launcher

import android.annotation.SuppressLint
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
import android.view.MotionEvent
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
    private var lastStateSave = 0L

    private val overlays = HashMap<String, OverlayController>()
    private val lastBounds = HashMap<String, Rect>()
    private val positionPreset = HashMap<String, Int>()
    private val statePrefs by lazy {
        getSharedPreferences("service_state", Context.MODE_PRIVATE)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            acquireWakeLock()
            ensureKeptProcesses()
            handler.postDelayed(this, WATCH_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        restoreState()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "apk-launcher:keep-watchdog",
        )
        acquireWakeLock()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val lock = wakeLock ?: return
        if (!lock.isHeld) lock.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val list = intent?.getStringArrayListExtra(MainActivity.KEEP_EXTRA)
        val pins = intent?.getStringArrayListExtra(MainActivity.PIN_EXTRA)
        if (list != null) {
            kept.clear()
            kept.addAll(list)
            pinned.clear()
            pinned.addAll(pins ?: emptyList())
            kept.addAll(pinned)
        } else {
            val keeper = AppKeeper(this)
            kept.clear()
            kept.addAll(keeper.list())
            pinned.clear()
            pinned.addAll(keeper.pinnedList())
            kept.addAll(pinned)
        }
        val active = activePackages()
        intent?.getStringExtra(EXTRA_REACTIVATE)?.let { pkg ->
            reluctant.remove(pkg)
            forceRelaunchTime.remove(pkg)
            lastAlive.remove(pkg)
        }
        reluctant.retainAll(active)
        forceRelaunchTime.keys.retainAll(active)
        lastAlive.keys.retainAll(active)

        val startedForeground = runCatching {
            startForeground(NOTIF_ID, buildNotification())
            true
        }.getOrDefault(false)
        if (!startedForeground) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> intent.getStringExtra(EXTRA_PKG)?.let { pkg ->
                val requestedBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_BOUNDS, Rect::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_BOUNDS) as? Rect
                }
                showOverlay(pkg, requestedBounds)
            }
            ACTION_HIDE_OVERLAY -> intent.getStringExtra(EXTRA_PKG)?.let { hideOverlay(it) }
        }

        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        if (activePackages().isEmpty() && overlays.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun ensureKeptProcesses() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val current = am.runningAppProcesses ?: emptyList()
        val active = activePackages()
        val launcherFocused = isLauncherFocused(am, current)
        val now = SystemClock.elapsedRealtime()

        for (pkg in active) {
            val alive = current.any { isProcessForPackage(it, pkg) }
            val lastAliveAt = lastAlive[pkg] ?: 0L
            val lastForced = forceRelaunchTime[pkg] ?: 0L
            if (alive) {
                lastAlive[pkg] = now
                if (pkg in pinned && !overlays.containsKey(pkg)) showOverlay(pkg)
                if (lastForced != 0L && lastAliveAt != 0L && now - lastForced > RELAUNCH_MIN_GAP_MS) {
                    reluctant.remove(pkg)
                }
                continue
            }
            hideOverlay(pkg)
            if (pkg in reluctant || now - lastAliveAt < RELAUNCH_MIN_GAP_MS) {
                if (lastForced != 0L && now - lastAliveAt < RELAUNCH_MIN_GAP_MS) {
                    reluctant.add(pkg)
                }
                continue
            }
            if (now - lastForced < RELAUNCH_MIN_GAP_MS) continue
            if (launcherFocused) continue
            if (relaunch(pkg) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this))
            ) {
                forceRelaunchTime[pkg] = now
            }
        }

        for (pkg in overlays.keys.toList()) {
            if (current.none { isProcessForPackage(it, pkg) }) hideOverlay(pkg)
        }

        if (now - lastStateSave >= STATE_SAVE_INTERVAL_MS) {
            saveState()
            lastStateSave = now
        }
        if (activePackages().isEmpty() && overlays.isEmpty()) {
            handler.removeCallbacks(watchdog)
            stopSelf()
        }
    }

    private fun activePackages(): Set<String> = (kept + pinned).toSet()

    @Suppress("DEPRECATION")
    private fun isProcessForPackage(
        process: android.app.ActivityManager.RunningAppProcessInfo,
        pkg: String,
    ): Boolean =
        process.importance > 0 &&
            process.importance < android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY &&
            (process.processName == pkg || process.processName.startsWith("$pkg:"))

    @Suppress("DEPRECATION")
    private fun isLauncherFocused(
        am: android.app.ActivityManager,
        current: List<android.app.ActivityManager.RunningAppProcessInfo>,
    ): Boolean {
        val topPackage = runCatching {
            am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        }.getOrNull()
        if (topPackage != null) return topPackage == packageName
        return current.any {
            it.processName == packageName &&
                it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun restoreState() {
        reluctant.clear()
        reluctant.addAll(statePrefs.getStringSet(STATE_RELUCTANT, emptySet()).orEmpty())
        forceRelaunchTime.clear()
        lastAlive.clear()
        val packages = statePrefs.getStringSet(STATE_PACKAGES, emptySet()).orEmpty() +
            reluctant
        val now = SystemClock.elapsedRealtime()
        for (pkg in packages) {
            val forced = statePrefs.getLong(STATE_FORCE_PREFIX + pkg, 0L)
            val alive = statePrefs.getLong(STATE_ALIVE_PREFIX + pkg, 0L)
            forceRelaunchTime[pkg] = if (forced > now) 0L else forced
            lastAlive[pkg] = if (alive > now) 0L else alive
        }
    }

    private fun saveState() {
        val packages = reluctant + forceRelaunchTime.keys + lastAlive.keys
        val editor = statePrefs.edit()
            .putStringSet(STATE_PACKAGES, packages)
            .putStringSet(STATE_RELUCTANT, reluctant.toSet())
        forceRelaunchTime.forEach { (pkg, time) ->
            editor.putLong(STATE_FORCE_PREFIX + pkg, time)
        }
        lastAlive.forEach { (pkg, time) ->
            editor.putLong(STATE_ALIVE_PREFIX + pkg, time)
        }
        editor.apply()
    }

    private fun relaunch(pkg: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
            return false
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching {
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            if (pkg in pinned) {
                val options = freeformOptions(lastBounds[pkg] ?: miniBounds())
                if (options != null) {
                    startActivity(launch, options)
                    showOverlay(pkg)
                } else {
                    hideOverlay(pkg)
                    startActivity(launch)
                }
            } else {
                launch.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                startActivity(launch)
            }
            true
        }.getOrDefault(false)
    }

    private fun freeformOptions(bounds: Rect? = null): android.os.Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !supportsFreeform()) return null
        val r = bounds ?: miniBounds()
        val opts = android.app.ActivityOptions.makeBasic().apply { setLaunchBounds(r) }
        if (!tryFreeform(opts)) return null
        return opts.toBundle()
    }

    private fun tryFreeform(options: android.app.ActivityOptions): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val windowing = runCatching {
            android.app.ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(options, WINDOWING_MODE_FREEFORM)
            true
        }.getOrDefault(false)
        if (windowing || Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return windowing
        return runCatching {
            android.app.ActivityOptions::class.java
                .getMethod("setLaunchStackId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(options, FREEFORM_STACK_ID)
            true
        }.getOrDefault(false)
    }

    private fun supportsFreeform(): Boolean =
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)

    private fun miniBounds(): Rect {
        val width = screenWidth()
        val height = screenHeight()
        val w = (width * 0.62f).toInt()
        val h = (height * 0.6f).toInt()
        val x = width - w
        val y = height - h
        return Rect(x, y, x + w, y + h)
    }

    // ================= Оверлей кнопок мини-окна =================

    fun showOverlay(pkg: String, requestedBounds: Rect? = null) {
        val clean = pkg.substringBefore(':')
        if (overlays.containsKey(clean)) return
        if (!Settings.canDrawOverlays(this)) return
        val bounds = clampOnScreen(requestedBounds ?: lastBounds[clean] ?: miniBounds())
        lastBounds[clean] = bounds
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = Gradient.drawRound(0xcc101420.toInt(), dp(16))
            addView(ovBtn("\u2715") { detachWindow(clean) })
            addView(ovBtn("\u25A3") { fullWindow(clean) })
            addView(ovBtn("+") { scaleWindow(clean, 1.2f) })
            addView(ovBtn("\u2212") { scaleWindow(clean, 0.82f) })
            addView(ovBtn("\u25C7") { moveWindow(clean) })
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            positionForBounds(this, bounds, 0, 0)
        }
        runCatching { wm.addView(bar, params) }.onFailure { return }
        overlays[clean] = OverlayController(bar, params)
        bar.post { positionOverlay(clean) }
    }

    fun hideOverlay(pkg: String) {
        val clean = pkg.substringBefore(':')
        val controller = overlays.remove(clean) ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(controller.bar)
        }
    }

    private fun positionOverlay(pkg: String) {
        val controller = overlays[pkg] ?: return
        val bounds = lastBounds[pkg] ?: return
        positionForBounds(
            controller.params,
            bounds,
            controller.bar.measuredWidth,
            controller.bar.measuredHeight,
        )
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(controller.bar, controller.params)
        }
    }

    private fun positionForBounds(
        params: WindowManager.LayoutParams,
        bounds: Rect,
        measuredWidth: Int,
        measuredHeight: Int,
    ) {
        val panelWidth = measuredWidth.takeIf { it > 0 } ?: dp(58)
        val panelHeight = measuredHeight.takeIf { it > 0 } ?: dp(260)
        val maxX = (screenWidth() - panelWidth - dp(4)).coerceAtLeast(dp(4))
        val maxY = (screenHeight() - panelHeight - dp(4)).coerceAtLeast(dp(4))
        params.x = (bounds.right - panelWidth - dp(6)).coerceIn(dp(4), maxX)
        params.y = bounds.top.coerceIn(dp(4), maxY)
    }

    private fun ovBtn(glyph: String, action: () -> Unit): TextView {
        val close = glyph == "\u2715"
        return TextView(this).apply {
            text = glyph
            setTextColor(if (close) 0xFFFF8080.toInt() else 0xFFF2F5FA.toInt())
            textSize = dp(15).toFloat()
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minimumWidth = dp(46)
            minimumHeight = dp(46)
            background = Gradient.drawRound(
                if (close) 0xCC33111F.toInt() else 0xDD1A2233.toInt(),
                dp(11),
            )
            setOnClickListener { action() }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.alpha = 0.45f
                    MotionEvent.ACTION_UP -> {
                        v.alpha = 1f
                        v.performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                true
            }
        }
    }

    private fun detachWindow(pkg: String) {
        val clean = pkg.substringBefore(':')
        hideOverlay(clean)
        pinned.remove(clean)
        reluctant.add(clean)
        saveState()
        AppKeeper(this).setPin(clean, false)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        runCatching { am.killBackgroundProcesses(clean) }
        handler.postDelayed({
            val still = runCatching {
                am.runningAppProcesses?.any { isProcessForPackage(it, clean) } ?: false
            }.getOrDefault(false)
            if (still) {
                toast("Приложение оставлено — закройте его в недавних приложениях")
            } else {
                toast("Приложение закрыто")
            }
        }, 600)
    }

    private fun scaleWindow(pkg: String, factor: Float) {
        val cur = lastBounds[pkg] ?: miniBounds().also { lastBounds[pkg] = it }
        val width = screenWidth()
        val height = screenHeight()
        var w = (cur.width() * factor).toInt()
        var h = (cur.height() * factor).toInt()
        w = w.coerceIn((width * 0.26f).toInt(), (width * 0.96f).toInt())
        h = h.coerceIn((height * 0.26f).toInt(), (height * 0.96f).toInt())
        var r = Rect(cur.left, cur.top, cur.left + w, cur.top + h)
        r = clampOnScreen(r)
        applyBounds(pkg, r)
    }

    private fun fullWindow(pkg: String) {
        applyBounds(pkg, Rect(0, 0, screenWidth(), screenHeight()))
    }

    private fun moveWindow(pkg: String) {
        val cur = lastBounds[pkg] ?: miniBounds().also { lastBounds[pkg] = it }
        val w = cur.width()
        val h = cur.height()
        val dw = screenWidth()
        val dh = screenHeight()
        val idx = ((positionPreset[pkg] ?: -1) + 1) % ANCHORS.size
        positionPreset[pkg] = idx
        val (grav, ax, ay) = ANCHORS[idx]
        val leftInset = dp(ax)
        val topInset = dp(ay)
        var r = when (grav) {
            Gravity.BOTTOM or Gravity.START -> Rect(leftInset, dh - h - topInset, w + leftInset, dh - topInset)
            Gravity.BOTTOM or Gravity.END -> Rect(dw - w - leftInset, dh - h - topInset, dw - leftInset, dh - topInset)
            Gravity.TOP or Gravity.END -> Rect(dw - w - leftInset, topInset, dw - leftInset, h + topInset)
            Gravity.CENTER or Gravity.END -> Rect(dw - w - leftInset, (dh - h) / 2, dw - leftInset, (dh + h) / 2)
            Gravity.CENTER -> Rect((dw - w) / 2, (dh - h) / 2, (dw + w) / 2, (dh + h) / 2)
            else -> Rect(leftInset, topInset, w + leftInset, h + topInset)
        }
        r = clampOnScreen(r)
        applyBounds(pkg, r)
    }

    private fun clampOnScreen(r: Rect): Rect {
        val x = r.left.coerceIn(0, (screenWidth() - r.width()).coerceAtLeast(0))
        val y = r.top.coerceIn(0, (screenHeight() - r.height()).coerceAtLeast(0))
        return Rect(x, y, x + r.width(), y + r.height())
    }

    private fun applyBounds(pkg: String, r: Rect) {
        val bounds = clampOnScreen(r)
        val cur = lastBounds[pkg]
        if (cur != null && cur == bounds) return
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        val options = launch?.let { freeformOptions(bounds) }
        val applied = if (launch != null && options != null) {
            runCatching {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(launch, options)
                true
            }.getOrDefault(false)
        } else {
            false
        }
        if (!applied) {
            toast("Изменение окна не поддерживается")
            return
        }
        lastBounds[pkg] = bounds
        positionOverlay(pkg)
        val pct = ((bounds.width() * 100f) / screenWidth()).toInt()
        val name = pkg.substringAfterLast('.').ifBlank { pkg }
        toast("$name: $pct% окна")
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun screenWidth(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds.width()
        }.getOrDefault(resources.displayMetrics.widthPixels)
    } else {
        resources.displayMetrics.widthPixels
    }

    private fun screenHeight(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds.height()
        }.getOrDefault(resources.displayMetrics.heightPixels)
    } else {
        resources.displayMetrics.heightPixels
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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

        val active = activePackages()
        val summary = active.take(3).joinToString(", ") { pkg ->
            pkg.substringAfterLast('.').ifBlank { pkg }
        }
        val text = if (active.isEmpty()) "Список пуст"
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
        saveState()
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
        const val EXTRA_BOUNDS = "bounds"
        const val EXTRA_REACTIVATE = "reactivate"
        private const val STATE_PACKAGES = "packages"
        private const val STATE_RELUCTANT = "reluctant"
        private const val STATE_FORCE_PREFIX = "force:"
        private const val STATE_ALIVE_PREFIX = "alive:"
        private const val CHANNEL_ID = "keep-alive"
        private const val NOTIF_ID = 7
        private const val WATCH_INTERVAL_MS = 2000L
        private const val STATE_SAVE_INTERVAL_MS = 30000L
        private const val RELAUNCH_MIN_GAP_MS = 15000L
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val FREEFORM_STACK_ID = 2
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