package dev.apk.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val kept = HashSet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = true

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
        val list = intent?.getStringArrayListExtra(MainActivity.KEEP_EXTRA)
        if (list != null) {
            kept.clear()
            kept.addAll(list)
        } else if (kept.isEmpty()) {
            // Система перезапустила процесс (START_STICKY / null intent) —
            // восстанавливаем список «держимых» из хранилища.
            kept.addAll(AppKeeper(this).list())
        }
        startForeground(NOTIF_ID, buildNotification())
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        if (kept.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun ensureKeptProcesses() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val current = am.runningAppProcesses?.map { it.processName } ?: emptyList()
        for (pkg in kept) {
            val alive = current.any { it == pkg || it.startsWith("$pkg:") }
            if (!alive) relaunch(pkg)
        }
    }

    private fun relaunch(pkg: String) {
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(launch)
            }
        }
    }

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
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "keep-alive"
        private const val NOTIF_ID = 7
        private const val WATCH_INTERVAL_MS = 2000L
    }
}