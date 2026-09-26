package dev.apk.launcher

import android.Manifest
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.util.Locale

class MainActivity : ComponentActivity() {

    private class AppEntry(
        val packageName: String,
        val label: String,
        val versionName: String,
        val icon: Drawable?,
    ) {
        val searchKey: String =
            "$label $packageName".lowercase(Locale.ROOT)
    }

    private data class ProcEntry(
        val packageName: String,
        val pid: Int,
        val importance: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val keeper by lazy { AppKeeper(applicationContext) }

    private var allApps: List<AppEntry> = emptyList()
    private var loadedPackages: List<String> = emptyList()
    private var processes: List<ProcEntry> = emptyList()
    private var runningBase: Set<String> = emptySet()
    private var keptPkgs: Set<String> = emptySet()
    private var pinnedPkgs: Set<String> = emptySet()
    private var query = ""
    private var showKeptOnly = false
    private var lastSig = ""
    private var lastProcSig = ""
    private var lastProcVisible = false
    private var stableTicks = 0
    private val iconCache = HashMap<String, Drawable>()

    private lateinit var statAppsNum: TextView
    private lateinit var statAppsLabel: TextView
    private lateinit var statAliveNum: TextView
    private lateinit var statSplitNum: TextView
    private lateinit var scrollContent: LinearLayout
    private lateinit var cardsBox: LinearLayout
    private lateinit var processPanel: LinearLayout
    private lateinit var updateBar: LinearLayout

    private val refresh = object : Runnable {
        override fun run() {
            loadProcesses()
            val changed = render()
            stableTicks = if (changed) 0 else stableTicks + 1
            val delay = when {
                stableTicks < 2 -> REFRESH_FAST_MS
                stableTicks < 6 -> REFRESH_MID_MS
                else -> REFRESH_SLOW_MS
            }
            handler.postDelayed(this, delay)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        maybeRequestNotifPermission()
        loadApps()
        keptPkgs = keeper.list().toSet()
        pinnedPkgs = keeper.pinnedList().toSet()
        keeper.syncService()
        showPendingOverlays()
        loadProcesses()
        lastSig = ""
        lastProcSig = ""
        stableTicks = 0
        render()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        checkForUpdate()
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun checkForUpdate(force: Boolean = false) {
        Updater.check(this, BuildConfig.VERSION_NAME, force) { latest, url ->
            updateUrl = url
            updateBar.removeAllViews()
            val title = tv("Обновление $latest доступно", 14f, Color.parseColor("#4ade80"), Typeface.BOLD)
            updateBar.addView(title)
            val btns = hBox()
            btns.gravity = Gravity.CENTER_VERTICAL
            btns.addView(miniBtn("скачать APK") { openUrl(updateUrl) }, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            btns.addView(miniBtn("позже") { updateBar.visibility = View.GONE }, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val blp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            blp.topMargin = dp(8)
            btns.layoutParams = blp
            updateBar.addView(btns)
            updateBar.visibility = View.VISIBLE
        }
    }

    private fun openUrl(url: String) {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme != "https" || !isTrustedUpdateHost(uri.host)) {
            toast("Некорректная ссылка обновления")
            return
        }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure { toast("Не удалось открыть ссылку") }
    }

    private fun isTrustedUpdateHost(host: String?): Boolean {
        val value = host?.lowercase(Locale.ROOT) ?: return false
        return value == "github.com" ||
            value == "githubusercontent.com" ||
            value.endsWith(".github.com") ||
            value.endsWith(".githubusercontent.com")
    }

    private var updateUrl = ""
    private val pendingOverlays = HashMap<String, Rect>()
    private val miniBoundsByPackage = HashMap<String, Rect>()

    private fun buildUi() {
        val root = vBox()
        root.setBackgroundColor(Color.parseColor("#0d0d12"))
        setContentView(root)

        val header = vBox()
        header.setPadding(dp(16), dp(12), dp(16), dp(4))
        val titleRow = hBox()
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        titleRow.addView(tv("APK Launcher", 20f, Color.WHITE, Typeface.BOLD))
        val versionTv = tv(BuildConfig.VERSION_NAME, 12f, Color.parseColor("#22c55e"), Typeface.BOLD)
        versionTv.setBackgroundResource(R.drawable.pill_keep_on)
        versionTv.setPadding(dp(8), dp(1), dp(8), dp(1))
        val vlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        vlp.setMargins(dp(10), 0, 0, 0)
        versionTv.layoutParams = vlp
        titleRow.addView(versionTv)
        header.addView(titleRow)

        updateBar = vBox()
        updateBar.visibility = View.GONE
        updateBar.setBackgroundResource(R.drawable.bg_update)
        updateBar.setPadding(dp(12), dp(10), dp(12), dp(10))
        val ublp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ublp.setMargins(dp(16), dp(10), dp(16), 0)
        updateBar.layoutParams = ublp
        header.addView(updateBar)

        val search = EditText(this)
        search.hint = "Поиск приложений…"
        search.setTextColor(Color.parseColor("#e8e8f0"))
        search.setHintTextColor(Color.parseColor("#66667a"))
        search.textSize = 15f
        search.isSingleLine = true
        search.setBackgroundResource(R.drawable.bg_search)
        search.setPadding(dp(12), 0, dp(12), 0)
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
        slp.setMargins(0, dp(10), 0, 0)
        search.layoutParams = slp
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                query = s?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                lastSig = ""
                renderCards()
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        header.addView(search)
        root.addView(header)

        val keepbar = hBox()
        keepbar.setPadding(dp(12), dp(10), dp(12), dp(4))
        val statApps = statColumn()
        statApps.setOnClickListener {
            showKeptOnly = !showKeptOnly
            lastSig = ""
            renderCards()
            updateStats()
        }
        statAppsNum = statsNum(statApps)
        statAppsLabel = statsLabel(statApps, "приложений")
        keepbar.addView(statApps)

        val statAlive = statColumn()
        statAliveNum = statsNum(statAlive)
        statsLabel(statAlive, "держится")
        keepbar.addView(statAlive)

        val statRefresh = statColumn()
        val refreshNum = statsNum(statRefresh)
        refreshNum.text = "⟳"
        refreshNum.setTextColor(Color.parseColor("#66667a"))
        statsLabel(statRefresh, "обновить")
        statRefresh.setOnClickListener {
            loadApps(force = true)
            keptPkgs = keeper.list().toSet()
            pinnedPkgs = keeper.pinnedList().toSet()
            loadProcesses()
            lastSig = ""
            lastProcSig = ""
            stableTicks = 0
            render()
            checkForUpdate(force = true)
        }
        keepbar.addView(statRefresh)

        val statSplit = statColumn()
        statSplitNum = statsNum(statSplit)
        statsLabel(statSplit, "все мини")
        statSplit.setOnClickListener { openKeptInMiniWindows() }
        keepbar.addView(statSplit)
        root.addView(keepbar)

        val scroller = ScrollView(this)
        scroller.isFillViewport = false
        scrollContent = vBox()
        scroller.addView(scrollContent, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        cardsBox = vBox()
        cardsBox.setPadding(0, dp(4), 0, dp(4))
        scrollContent.addView(cardsBox)

        processPanel = vBox()
        processPanel.setBackgroundResource(R.drawable.bg_panel)
        val plp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        plp.setMargins(dp(16), dp(8), dp(16), dp(16))
        processPanel.layoutParams = plp
        processPanel.setPadding(dp(12), dp(12), dp(12), dp(12))
        scrollContent.addView(processPanel)
    }

    private fun render(): Boolean {
        val sig = sig()
        var changed = false
        if (sig != lastSig) {
            renderCards()
            lastSig = sig
            changed = true
        }
        if (updateStats()) changed = true
        if (updateProcessPane()) changed = true
        return changed
    }

    private fun renderCards() {
        cardsBox.removeAllViews()
        val filtered = allApps.filter { matches(it) }
        if (filtered.isEmpty()) {
            val empty = tv("Ничего не найдено", 14f, Color.parseColor("#66667a"))
            empty.gravity = Gravity.CENTER
            empty.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140))
            cardsBox.addView(empty)
            return
        }
        val cols = maxOf(1, resources.configuration.screenWidthDp / MIN_CARD_WIDTH_DP)
        var i = 0
        while (i < filtered.size) {
            val row = hBox()
            row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            for (c in 0 until cols) {
                val cell = vBox()
                cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                cell.setPadding(dp(4), dp(6), dp(4), dp(6))
                if (i + c < filtered.size) cell.addView(buildCard(filtered[i + c]))
                row.addView(cell)
            }
            cardsBox.addView(row)
            i += cols
        }
    }

    private fun buildCard(app: AppEntry): View {
        val kept = app.packageName in keptPkgs
        val pinned = app.packageName in pinnedPkgs
        val alive = running(app.packageName)

        val card = vBox()
        card.setBackgroundResource(R.drawable.bg_card)
        card.setPadding(dp(12), dp(12), dp(12), dp(10))

        val main = vBox()
        main.gravity = Gravity.CENTER_HORIZONTAL
        main.setOnClickListener { launch(app.packageName) }
        card.addView(main)

        val icon = ImageView(this)
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        val ilp = LinearLayout.LayoutParams(dp(52), dp(52))
        ilp.gravity = Gravity.CENTER_HORIZONTAL
        icon.layoutParams = ilp
        icon.setImageDrawable(app.icon ?: getDrawable(android.R.drawable.sym_def_app_icon))
        main.addView(icon)

        val label = tv(app.label, 14f, Color.WHITE, Typeface.BOLD)
        label.gravity = Gravity.CENTER
        label.maxLines = 2
        label.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        main.addView(label)

        val ver = tv(
            if (app.versionName.isEmpty()) app.packageName else app.versionName,
            11f,
            Color.parseColor("#66667a"),
        )
        ver.gravity = Gravity.CENTER
        ver.maxLines = 1
        main.addView(ver)

        val row = hBox()
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        card.addView(row)

        row.addView(
            tv(if (alive) "работает" else "спит", 11f,
                if (alive) Color.parseColor("#4ade80") else Color.parseColor("#66667a")),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        val keepBtn = Button(this)
        keepBtn.text = if (kept) "снять keep" else "keep"
        keepBtn.textSize = 12f
        keepBtn.isAllCaps = false
        keepBtn.minimumWidth = 0
        keepBtn.minimumHeight = 0
        keepBtn.setTextColor(if (kept) Color.parseColor("#4ade80") else Color.parseColor("#b9b9cc"))
        keepBtn.setBackgroundResource(if (kept) R.drawable.pill_keep_on else R.drawable.pill_keep_off)
        keepBtn.setOnClickListener { toggleKeep(app.packageName) }
        row.addView(keepBtn)

        val btnRow = hBox()
        btnRow.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        }

        val pinBtn = miniBtn("пин") { togglePin(app.packageName) }
        pinBtn.setTextColor(if (pinned) Color.parseColor("#4ade80") else Color.parseColor("#b9b9cc"))
        pinBtn.setBackgroundResource(if (pinned) R.drawable.pill_keep_on else R.drawable.pill_keep_off)
        btnRow.addView(pinBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        btnRow.addView(miniBtn("окно") { launchAdjacent(app.packageName) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(miniBtn("мини") { launchMini(app.packageName) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(btnRow)
        return card
    }

    private fun updateStats(): Boolean {
        val appsNum = if (showKeptOnly) keptPkgs.size else allApps.size
        val appsLabel = if (showKeptOnly) "на keep" else "приложений"
        val aliveNum = keptPkgs.count { running(it) }
        val keepNum = keptPkgs.size
        var changed = false
        if (statAppsNum.text.toString() != appsNum.toString()) {
            statAppsNum.text = appsNum.toString()
            changed = true
        }
        if (statAppsLabel.text.toString() != appsLabel) {
            statAppsLabel.text = appsLabel
            changed = true
        }
        if (statAliveNum.text.toString() != aliveNum.toString()) {
            statAliveNum.text = aliveNum.toString()
            changed = true
        }
        if (statSplitNum.text.toString() != keepNum.toString()) {
            statSplitNum.text = keepNum.toString()
            changed = true
        }
        return changed
    }

    private fun updateProcessPane(): Boolean {
        val visible = processes.isNotEmpty() || keptPkgs.isNotEmpty()
        if (!visible) {
            if (!lastProcVisible) return false
            lastProcVisible = false
            lastProcSig = ""
            processPanel.removeAllViews()
            processPanel.visibility = View.GONE
            return true
        }
        val sig = procSig()
        if (visible == lastProcVisible && sig == lastProcSig) return false
        lastProcVisible = visible
        lastProcSig = sig
        processPanel.removeAllViews()
        processPanel.visibility = View.VISIBLE
        val title = tv("Процессы (${processes.size})", 15f, Color.WHITE, Typeface.BOLD)
        processPanel.addView(title)
        for (p in processes) {
            processPanel.addView(buildProcRow(p))
        }
        return true
    }

    private fun procSig(): String {
        if (processes.isEmpty() && keptPkgs.isEmpty()) return ""
        val sb = StringBuilder(processes.size * 24)
        for (p in processes) {
            sb.append(p.packageName).append('/').append(p.pid)
                .append('/').append(p.importance)
                .append(if (p.packageName.substringBefore(':') in keptPkgs) '1' else '0')
                .append(if (p.packageName.substringBefore(':') in pinnedPkgs) '1' else '0')
                .append(';')
        }
        return sb.toString()
    }

    private fun buildProcRow(p: ProcEntry): View {
        val base = p.packageName.substringBefore(':')
        val kept = base in keptPkgs
        val pinned = base in pinnedPkgs
        val row = hBox()
        row.setBackgroundResource(R.drawable.bg_proc_row)
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        }
        row.setPadding(dp(8), dp(6), dp(8), dp(6))

        val icon = ImageView(this)
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        icon.layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        icon.setImageDrawable(iconFor(base))
        row.addView(icon)

        val name = tv(p.packageName, 13f, Color.WHITE)
        name.maxLines = 1
        name.ellipsize = android.text.TextUtils.TruncateAt.END
        val nlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nlp.setMargins(dp(8), 0, dp(8), 0)
        name.layoutParams = nlp
        row.addView(name)

        val state = tv(
            if (kept) "keep" else p.importance,
            11f,
            if (kept) Color.parseColor("#4ade80") else Color.parseColor("#f87171"),
        )
        state.setBackgroundResource(if (kept) R.drawable.pill_state_on else R.drawable.pill_state_off)
        state.gravity = Gravity.CENTER
        state.setPadding(dp(8), dp(2), dp(8), dp(2))
        row.addView(state)

        val pinBtn = miniBtn("пин") { togglePin(base) }
        pinBtn.setTextColor(if (pinned) Color.parseColor("#4ade80") else Color.parseColor("#b9b9cc"))
        pinBtn.setBackgroundResource(if (pinned) R.drawable.pill_keep_on else R.drawable.pill_keep_off)
        row.addView(pinBtn)
        row.addView(miniBtn("окно") { launchAdjacent(base) })
        row.addView(miniBtn("мини") { launchMini(base) })
        row.addView(miniBtn("запустить") { launch(base) })
        row.addView(miniBtn(if (kept) "снять keep" else "keep") { toggleKeep(base) })
        return row
    }

    private fun iconFor(pkg: String): Drawable? {
        iconCache[pkg]?.let { return it }
        val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
            ?: getDrawable(android.R.drawable.sym_def_app_icon)
            ?: return null
        iconCache[pkg] = icon
        return icon
    }

    private fun miniBtn(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 11f
        b.isAllCaps = false
        b.minimumWidth = 0
        b.minimumHeight = 0
        b.setTextColor(Color.parseColor("#b9b9cc"))
        b.setBackgroundResource(R.drawable.pill_keep_off)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(4), 0, 0, 0)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toggleKeep(pkg: String) {
        val keep = pkg !in keptPkgs
        if (keep && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasOverlayPermission()) {
            toast("Для автоперезапуска включите отображение поверх других окон")
            requestOverlayPermission()
            return
        }
        if (!keeper.set(pkg, keep)) {
            toast("Не удалось изменить keep-alive")
            return
        }
        keptPkgs = if (keep) keptPkgs + pkg else keptPkgs - pkg
        if (!keep) {
            pinnedPkgs = pinnedPkgs - pkg
            overlayFor(pkg, show = false)
        }
        lastSig = ""
        renderCards()
        updateStats()
        updateProcessPane()
    }

    private fun launch(pkg: String) {
        val ok = runCatching {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            true
        }.getOrDefault(false)
        if (!ok) toast("Не удалось запустить $pkg")
    }

    private fun launchAdjacent(pkg: String) {
        val ok = runCatching {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
            i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )
            startActivity(i, ActivityOptions.makeBasic().toBundle())
            true
        }.getOrDefault(false)
        if (!ok) toast("Не удалось открыть $pkg рядом")
    }

    private fun launchMini(pkg: String) {
        launchMini(pkg, 0)
    }

    private fun launchMini(pkg: String, offset: Int) {
        val launch = runCatching {
            packageManager.getLaunchIntentForPackage(pkg)
        }.getOrNull()
        if (launch == null) {
            toast("Не удалось найти приложение $pkg")
            return
        }
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS
        )
        val requestedOffset = if (offset == 0 && pkg !in miniBoundsByPackage) {
            miniBoundsByPackage.size * 2
        } else {
            offset
        }
        val bounds = miniBoundsByPackage[pkg] ?: miniWindowBounds(requestedOffset).also {
            miniBoundsByPackage[pkg] = Rect(it)
        }
        val launchedInWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                val options = ActivityOptions.makeBasic().apply {
                    setLaunchBounds(bounds)
                    setFreeformModeBestEffort()
                }
                startActivity(launch, options.toBundle())
                true
            }.getOrDefault(false)
        } else {
            false
        }
        if (launchedInWindow) {
            showOverlayOrRequest(pkg, bounds)
            return
        }
        val opened = runCatching {
            startActivity(launch)
            true
        }.getOrDefault(false)
        if (opened) {
            toast("Свободное окно недоступно — открыто обычное окно")
        } else {
            toast("Не удалось открыть $pkg")
        }
    }

    private fun openKeptInMiniWindows() {
        val pkgs = keptPkgs.sorted()
        if (pkgs.isEmpty()) {
            toast("Нет приложений на keep")
            return
        }
        pkgs.forEachIndexed { idx, pkg -> launchMini(pkg, idx) }
    }

    private fun miniWindowBounds(offset: Int): Rect {
        val w = (screenWidth() * 0.62f).toInt()
        val h = (screenHeight() * 0.6f).toInt()
        val gap = dp(14)
        val maxX = (screenWidth() - w - gap).coerceAtLeast(0)
        val maxY = (screenHeight() - h - gap).coerceAtLeast(0)
        val step = dp(32)
        val maxSteps = minOf(maxX, maxY) / step
        val steps = offset.coerceIn(0, maxSteps)
        val x = (maxX - steps * step).coerceAtLeast(0)
        val y = (maxY - steps * step).coerceAtLeast(0)
        return Rect(x, y, x + w, y + h)
    }

    private fun ActivityOptions.setFreeformModeBestEffort() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(this, WINDOWING_MODE_FREEFORM)
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            runCatching {
                ActivityOptions::class.java
                    .getMethod("setLaunchStackId", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(this, FREEFORM_STACK_ID)
            }
        }
    }

    private fun togglePin(pkg: String) {
        val clean = pkg.substringBefore(':')
        val pin = clean !in pinnedPkgs
        if (!keeper.setPin(clean, pin)) {
            toast("Не удалось изменить мини-окно")
            return
        }
        pinnedPkgs = if (pin) pinnedPkgs + clean else pinnedPkgs - clean
        if (pin) keptPkgs = keptPkgs + clean
        lastSig = ""
        renderCards()
        if (pin) launchMini(clean) else overlayFor(clean, show = false)
    }

    private fun showOverlayOrRequest(pkg: String, bounds: Rect) {
        val clean = pkg.substringBefore(':')
        if (hasOverlayPermission()) {
            pendingOverlays.remove(clean)
            overlayFor(clean, show = true, bounds = bounds)
            return
        }
        pendingOverlays[clean] = Rect(bounds)
        requestOverlayPermission()
        toast("Включите «отображение поверх других окон» для кнопок мини-окна")
    }

    private fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }.onFailure {
            toast("Не удалось открыть настройки разрешения")
        }
    }

    private fun showPendingOverlays() {
        if (!hasOverlayPermission() || pendingOverlays.isEmpty()) return
        val pending = pendingOverlays.toMap()
        pendingOverlays.clear()
        pending.forEach { (pkg, bounds) -> overlayFor(pkg, show = true, bounds = bounds) }
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun overlayFor(pkg: String, show: Boolean, bounds: Rect? = null) {
        val clean = pkg.substringBefore(':')
        val i = Intent(this, KeepAliveService::class.java)
            .setAction(
                if (show) KeepAliveService.ACTION_SHOW_OVERLAY
                else KeepAliveService.ACTION_HIDE_OVERLAY
            )
            .putExtra(KeepAliveService.EXTRA_PKG, clean)
        if (bounds != null) i.putExtra(KeepAliveService.EXTRA_BOUNDS, bounds)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        }.onFailure {
            toast("Не удалось запустить панель мини-окна")
        }
    }

    private fun loadApps(force: Boolean = false) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val packages = ArrayList<String>(resolved.size)
        val seen = HashSet<String>(resolved.size * 2)
        for (ri in resolved) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            if (seen.add(pkg)) packages.add(pkg)
        }
        packages.sort()
        if (!force && packages == loadedPackages) return
        loadedPackages = packages
        iconCache.keys.retainAll(packages.toSet())
        val apps = ArrayList<AppEntry>(packages.size)
        for (pkg in packages) {
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val version = runCatching { pm.getPackageInfo(pkg, 0).versionName ?: "" }.getOrDefault("")
            val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            apps.add(AppEntry(pkg, label, version, icon))
        }
        apps.sortBy { it.label.lowercase(Locale.ROOT) }
        allApps = apps
    }

    @Suppress("DEPRECATION")
    private fun loadProcesses() {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val running = am.runningAppProcesses ?: emptyList()
        val list = ArrayList<ProcEntry>(running.size)
        val base = HashSet<String>(running.size * 2)
        for (p in running) {
            if (p.importance <= 0 ||
                p.importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY
            ) {
                continue
            }
            list.add(ProcEntry(p.processName, p.pid, importanceName(p.importance)))
            base.add(p.processName.substringBefore(':'))
        }
        processes = list
        runningBase = base
    }

    @Suppress("DEPRECATION")
    private fun importanceName(importance: Int): String = when {
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY -> "empty"
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "background"
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "sleeping"
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "сервис"
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        else -> "unknown"
    }

    private fun running(pkg: String): Boolean = runningBase.contains(pkg)

    private fun matches(a: AppEntry): Boolean {
        if (showKeptOnly && a.packageName !in keptPkgs) return false
        if (query.isEmpty()) return true
        val q = query
        return a.searchKey.contains(q) || a.packageName.contains(q)
    }

    private fun sig(): String {
        val sb = StringBuilder(allApps.size * 6)
        sb.append(query).append('|').append(showKeptOnly).append('|')
        for (a in allApps) {
            if (!matches(a)) continue
            sb.append(a.packageName)
            sb.append(if (a.packageName in keptPkgs) '1' else '0')
            sb.append(if (a.packageName in pinnedPkgs) '1' else '0')
            sb.append(if (running(a.packageName)) '1' else '0')
        }
        return sb.toString()
    }

    private fun statColumn(): LinearLayout {
        val v = vBox()
        v.gravity = Gravity.CENTER
        v.setBackgroundResource(R.drawable.bg_stat)
        v.setPadding(dp(10), dp(8), dp(10), dp(8))
        v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(4), 0, dp(4), 0)
        }
        return v
    }

    private fun statsNum(parent: LinearLayout): TextView {
        val t = tv("0", 22f, Color.WHITE, Typeface.BOLD)
        parent.addView(t)
        return t
    }

    private fun statsLabel(parent: LinearLayout, text: String): TextView {
        val t = tv(text, 11f, Color.parseColor("#8a8a9e"))
        parent.addView(t)
        return t
    }

    private fun hBox(): LinearLayout {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.HORIZONTAL
        v.gravity = Gravity.CENTER_VERTICAL
        return v
    }

    private fun vBox(): LinearLayout {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        return v
    }

    private fun tv(text: String, sp: Float, color: Int, style: Int = Typeface.NORMAL): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sp
        t.setTextColor(color)
        t.setTypeface(Typeface.DEFAULT, style)
        return t
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val KEEP_EXTRA = "keep_pkgs"
        const val PIN_EXTRA = "pin_pkgs"
        private const val REFRESH_FAST_MS = 2500L
        private const val REFRESH_MID_MS = 5000L
        private const val REFRESH_SLOW_MS = 10000L
        private const val MIN_CARD_WIDTH_DP = 150
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val FREEFORM_STACK_ID = 2
    }
}