package dev.apk.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private var stateDirty = true
    private var active: Set<String> = emptySet()
    private var screenOn = true
    private var lastNotificationText: String? = null
    private var cachedNotification: Notification? = null

    private val overlays = HashMap<String, OverlayController>()
    private val lastBounds = HashMap<String, Rect>()
    private val positionPreset = HashMap<String, Int>()
    private val statePrefs by lazy {
        getSharedPreferences("service_state", Context.MODE_PRIVATE)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            var next = WATCH_INTERVAL_SLOW_MS
            acquireWakeLock()
            try {
                next = ensureKeptProcesses()
            } finally {
                releaseWakeLock()
            }
            if (running) handler.postDelayed(this, next)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                else -> return
            }
            if (!running) return
            handler.removeCallbacks(watchdog)
            handler.post(watchdog)
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
        wakeLock?.setReferenceCounted(false)
        screenOn = pm.isInteractive
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, screenFilter)
            }
        }
    }

    private fun acquireWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) return
        runCatching { lock.acquire(WAKE_LOCK_WINDOW_MS) }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
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
        active = activePackages()
        intent?.getStringExtra(EXTRA_REACTIVATE)?.let { pkg ->
            reluctant.remove(pkg)
            forceRelaunchTime.remove(pkg)
            lastAlive.remove(pkg)
        }
        reluctant.retainAll(active)
        forceRelaunchTime.keys.retainAll(active)
        lastAlive.keys.retainAll(active)
        lastNotificationText = null
        cachedNotification = null

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
        restoreOverlays()

        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        if (active.isEmpty() && overlays.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun ensureKeptProcesses(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val current = am.runningAppProcesses ?: emptyList()
        val names = runningProcessNames(current)
        val now = SystemClock.elapsedRealtime()
        val targets = active
        var deadCount = 0
        var relaunchPending = false
        var anyRelaunched = false
        var launcherFocused = false

        for (pkg in targets) {
            if (isPackageRunning(names, pkg)) {
                lastAlive[pkg] = now
                if (pkg in pinned && !overlays.containsKey(pkg)) {
                    showOverlay(pkg)
                    stateDirty = true
                }
                if (forceRelaunchTime[pkg]?.let { now - it > RELAUNCH_MIN_GAP_MS } == true) {
                    if (reluctant.remove(pkg)) stateDirty = true
                }
                continue
            }
            deadCount++
            if (overlays.containsKey(pkg)) hideOverlay(pkg, persist = false)
            val lastAliveAt = lastAlive[pkg] ?: 0L
            val lastForced = forceRelaunchTime[pkg] ?: 0L
            if (pkg in reluctant || now - lastAliveAt < RELAUNCH_MIN_GAP_MS) {
                if (lastForced != 0L && now - lastAliveAt < RELAUNCH_MIN_GAP_MS &&
                    reluctant.add(pkg)
                ) {
                    stateDirty = true
                }
                if (pkg !in reluctant) relaunchPending = true
                continue
            }
            if (now - lastForced < RELAUNCH_MIN_GAP_MS) {
                relaunchPending = true
                continue
            }
            if (!launcherFocused) launcherFocused = isLauncherFocused(am, current)
            if (launcherFocused) continue
            if (relaunch(pkg) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this))
            ) {
                forceRelaunchTime[pkg] = now
                stateDirty = true
                anyRelaunched = true
            }
            relaunchPending = true
        }

        if (overlays.isNotEmpty()) {
            for (pkg in overlays.keys.toList()) {
                if (!isPackageRunning(names, pkg)) hideOverlay(pkg, persist = false)
            }
        }

        if (stateDirty || now - lastStateSave >= STATE_SAVE_INTERVAL_MS) saveState()

        if (targets.isEmpty() && overlays.isEmpty()) {
            handler.removeCallbacks(watchdog)
            stopSelf()
            return WATCH_INTERVAL_SLOW_MS
        }
        if (relaunchPending || anyRelaunched) return WATCH_INTERVAL_FAST_MS
        if (deadCount > 0) return if (launcherFocused) WATCH_INTERVAL_MID_MS else WATCH_INTERVAL_SLOW_MS
        return if (screenOn) WATCH_INTERVAL_MID_MS else WATCH_INTERVAL_SLOW_MS
    }

    private fun activePackages(): Set<String> {
        if (kept.isEmpty() && pinned.isEmpty()) return emptySet()
        return HashSet<String>(kept.size + pinned.size).apply {
            addAll(kept)
            addAll(pinned)
        }
    }

    @Suppress("DEPRECATION")
    private fun runningProcessNames(
        current: List<android.app.ActivityManager.RunningAppProcessInfo>,
    ): Set<String> {
        val names = HashSet<String>(current.size * 2)
        for (process in current) {
            if (process.importance > 0 &&
                process.importance < android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY
            ) {
                names.add(process.processName)
            }
        }
        return names
    }

    @Suppress("DEPRECATION")
    private fun isPackageRunning(names: Set<String>, pkg: String): Boolean {
        if (names.contains(pkg)) return true
        val prefix = "$pkg:"
        for (name in names) {
            if (name.length > prefix.length && name.startsWith(prefix)) return true
        }
        return false
    }

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
        val boundsPackages = statePrefs.getStringSet(STATE_BOUNDS, emptySet()).orEmpty()
        for (pkg in boundsPackages) {
            val prefix = STATE_BOUND_PREFIX + pkg
            if (statePrefs.getBoolean(prefix, false)) {
                lastBounds[pkg] = Rect(
                    statePrefs.getInt("$prefix:left", 0),
                    statePrefs.getInt("$prefix:top", 0),
                    statePrefs.getInt("$prefix:right", 0),
                    statePrefs.getInt("$prefix:bottom", 0),
                )
            }
        }
    }

    private fun saveState() {
        stateDirty = false
        lastStateSave = SystemClock.elapsedRealtime()
        val packages = reluctant + forceRelaunchTime.keys + lastAlive.keys
        val bounds = lastBounds.filterValues { it.width() > 0 && it.height() > 0 }
        val editor = statePrefs.edit()
            .putStringSet(STATE_PACKAGES, packages)
            .putStringSet(STATE_RELUCTANT, reluctant.toSet())
            .putStringSet(STATE_OVERLAYS, overlays.keys.toSet())
            .putStringSet(STATE_BOUNDS, bounds.keys)
        forceRelaunchTime.forEach { (pkg, time) ->
            editor.putLong(STATE_FORCE_PREFIX + pkg, time)
        }
        lastAlive.forEach { (pkg, time) ->
            editor.putLong(STATE_ALIVE_PREFIX + pkg, time)
        }
        bounds.forEach { (pkg, rect) ->
            val prefix = STATE_BOUND_PREFIX + pkg
            editor.putBoolean(prefix, true)
            editor.putInt("$prefix:left", rect.left)
            editor.putInt("$prefix:top", rect.top)
            editor.putInt("$prefix:right", rect.right)
            editor.putInt("$prefix:bottom", rect.bottom)
        }
        editor.apply()
    }

    private fun persistState() {
        if (!stateDirty) return
        saveState()
    }

    private fun relaunch(pkg: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
            return false
        }
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        val r = bounds ?: miniBounds()
        val opts = android.app.ActivityOptions.makeBasic().apply {
            setLaunchBounds(r)
            setFreeformModeBestEffort()
        }
        return opts.toBundle()
    }

    private fun android.app.ActivityOptions.setFreeformModeBestEffort() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                android.app.ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(this, WINDOWING_MODE_FREEFORM)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            runCatching {
                android.app.ActivityOptions::class.java
                    .getMethod("setLaunchStackId", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(this, FREEFORM_STACK_ID)
            }
        }
    }

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

    private fun restoreOverlays() {
        val stored = statePrefs.getStringSet(STATE_OVERLAYS, emptySet()).orEmpty().toList()
        for (pkg in stored) {
            if (pkg !in overlays) showOverlay(pkg, lastBounds[pkg])
        }
    }

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
        stateDirty = true
        persistState()
        bar.post { positionOverlay(clean) }
    }

    fun hideOverlay(pkg: String, persist: Boolean = true) {
        val clean = pkg.substringBefore(':')
        val controller = overlays.remove(clean)
        if (controller == null) return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(controller.bar)
        }
        stateDirty = true
        if (persist) persistState()
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
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                }
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun detachWindow(pkg: String) {
        val clean = pkg.substringBefore(':')
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val taskId = runCatching {
            am.getRunningTasks(100).firstOrNull {
                it.baseActivity?.packageName == clean ||
                    it.topActivity?.packageName == clean
            }?.id
        }.getOrNull()
        val moved = taskId?.let {
            runCatching {
                am.javaClass.getMethod("moveTaskToBack", Int::class.javaPrimitiveType)
                    .invoke(am, it) as? Boolean
            }.getOrNull() == true
        } ?: false
        val hidden = moved || runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        hideOverlay(clean)
        pinned.remove(clean)
        reluctant.add(clean)
        stateDirty = true
        saveState()
        AppKeeper(this).set(clean, false)
        toast(if (hidden) "Окно скрыто" else "Не удалось скрыть окно")
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
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        stateDirty = true
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
        val targets = activePackages()
        val summary = targets.take(3).joinToString(", ") { pkg ->
            pkg.substringAfterLast('.').ifBlank { pkg }
        }
        val text = if (targets.isEmpty()) "Список пуст" else "Держу живыми: $summary"
        cachedNotification?.takeIf { lastNotificationText == text }?.let { return it }

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

        val notification = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("APK Launcher")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        lastNotificationText = text
        cachedNotification = notification
        return notification
    }

    override fun onDestroy() {
        running = false
        saveState()
        handler.removeCallbacks(watchdog)
        for (pkg in overlays.keys.toList()) hideOverlay(pkg, persist = false)
        runCatching { unregisterReceiver(screenReceiver) }
        releaseWakeLock()
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
        fun hasPersistedOverlays(context: Context): Boolean =
            context.getSharedPreferences("service_state", Context.MODE_PRIVATE)
                .getStringSet(STATE_OVERLAYS, emptySet())
                .orEmpty()
                .isNotEmpty()

        const val ACTION_SHOW_OVERLAY = "dev.apk.launcher.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "dev.apk.launcher.action.HIDE_OVERLAY"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_BOUNDS = "bounds"
        const val EXTRA_REACTIVATE = "reactivate"
        private const val STATE_PACKAGES = "packages"
        private const val STATE_RELUCTANT = "reluctant"
        private const val STATE_OVERLAYS = "overlays"
        private const val STATE_BOUNDS = "bounds"
        private const val STATE_FORCE_PREFIX = "force:"
        private const val STATE_ALIVE_PREFIX = "alive:"
        private const val STATE_BOUND_PREFIX = "bound:"
        private const val CHANNEL_ID = "keep-alive"
        private const val NOTIF_ID = 7
        private const val WATCH_INTERVAL_FAST_MS = 2000L
        private const val WATCH_INTERVAL_MID_MS = 6000L
        private const val WATCH_INTERVAL_SLOW_MS = 15000L
        private const val WAKE_LOCK_WINDOW_MS = 5000L
        private const val STATE_SAVE_INTERVAL_MS = 120000L
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