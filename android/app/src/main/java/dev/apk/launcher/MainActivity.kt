package dev.apk.launcher

import android.Manifest
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private class AppEntry(
        val packageName: String,
        val label: String,
        val versionName: String,
        val icon: Drawable?,
    )

    private data class ProcEntry(
        val packageName: String,
        val pid: Int,
        val importance: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val keeper by lazy { AppKeeper(applicationContext) }

    private var allApps: List<AppEntry> = emptyList()
    private var processes: List<ProcEntry> = emptyList()
    private var keptPkgs: Set<String> = emptySet()
    private var pinnedPkgs: Set<String> = emptySet()
    private var query = ""
    private var showKeptOnly = false
    private var lastSig = ""

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
            render()
            handler.postDelayed(this, REFRESH_MS)
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
        loadProcesses()
        lastSig = ""
        render()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        checkForUpdate()
    }

    override fun onPause() {
        // В PiP лаунчер остаётся видимым и «живым»: не останавливаем refresh.
        if (!isInPictureInPictureMode) handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            pipHiddenBar = updateBar.visibility
            pipHiddenPanel = processPanel.visibility
            updateBar.visibility = View.GONE
            processPanel.visibility = View.GONE
        } else {
            updateBar.visibility = pipHiddenBar
            updateProcessPane()
            handler.removeCallbacks(refresh)
            handler.post(refresh)
        }
    }

    private var pipHiddenBar = View.GONE
    private var pipHiddenPanel = View.GONE

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun checkForUpdate() {
        Updater.check(this, BuildConfig.VERSION_NAME) { latest, url ->
            updateUrl = url
            updateLatest = latest
            updateBar.removeAllViews()
            val title = tv("Обновление $latest доступно", 14f, Color.parseColor("#4ade80"), Typeface.BOLD)
            updateBar.addView(title)
            val btns = hBox()
            btns.gravity = Gravity.CENTER_VERTICAL
            btns.addView(miniBtn("открыть релиз") { openUrl(updateUrl) }, LinearLayout.LayoutParams(
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
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure { toast("Не удалось открыть ссылку") }
    }

    private var updateUrl = ""
    private var updateLatest = ""

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
        titleRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        val pipBtn = Button(this)
        pipBtn.text = "PiP"
        pipBtn.textSize = 12f
        pipBtn.isAllCaps = false
        pipBtn.minimumWidth = 0
        pipBtn.minimumHeight = 0
        pipBtn.setTextColor(Color.parseColor("#22c55e"))
        pipBtn.setBackgroundResource(R.drawable.pill_keep_on)
        val pipLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        pipLp.setMargins(dp(6), 0, 0, 0)
        pipBtn.layoutParams = pipLp
        pipBtn.setOnClickListener { enterPip() }
        titleRow.addView(pipBtn)
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
                query = s?.toString() ?: ""
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
            loadApps()
            keptPkgs = keeper.list().toSet()
            pinnedPkgs = keeper.pinnedList().toSet()
            loadProcesses()
            lastSig = ""
            render()
        }
        keepbar.addView(statRefresh)

        val statSplit = statColumn()
        statSplitNum = statsNum(statSplit)
        statsLabel(statSplit, "в окна")
        statSplit.setOnClickListener { openKeptInWindows() }
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

    private fun render() {
        val sig = sig()
        if (sig != lastSig) {
            renderCards()
            lastSig = sig
        }
        updateStats()
        updateProcessPane()
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

    private fun updateStats() {
        statAppsNum.text = if (showKeptOnly) keptPkgs.size.toString() else allApps.size.toString()
        statAppsLabel.text = if (showKeptOnly) "на keep" else "приложений"
        statAliveNum.text = keptPkgs.count { running(it) }.toString()
        statSplitNum.text = keptPkgs.size.toString()
    }

    private fun updateProcessPane() {
        processPanel.removeAllViews()
        if (processes.isEmpty() && keptPkgs.isEmpty()) {
            processPanel.visibility = View.GONE
            return
        }
        processPanel.visibility = View.VISIBLE
        val title = tv("Процессы (${processes.size})", 15f, Color.WHITE, Typeface.BOLD)
        processPanel.addView(title)
        for (p in processes) {
            processPanel.addView(buildProcRow(p))
        }
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
        icon.setImageDrawable(
            runCatching { packageManager.getApplicationIcon(p.packageName) }.getOrNull()
                ?: getDrawable(android.R.drawable.sym_def_app_icon),
        )
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
        if (!keeper.set(pkg, keep)) {
            toast("Не удалось изменить keep-alive")
            return
        }
        keptPkgs = if (keep) keptPkgs + pkg else keptPkgs - pkg
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
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(i, ActivityOptions.makeBasic().toBundle())
            true
        }.getOrDefault(false)
        if (!ok) toast("Не удалось открыть $pkg рядом")
    }

    private fun launchMini(pkg: String) {
        val ok = runCatching {
            val i = packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opts = ActivityOptions.makeBasic().setLaunchBounds(miniBounds())
            startActivity(i, opts.toBundle())
            true
        }.getOrDefault(false)
        if (!ok) toast("Не удалось открыть $pkg в мини-окне")
    }

    private fun miniBounds(): Rect {
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.62f).toInt()
        val h = (dm.heightPixels * 0.6f).toInt()
        val x = dm.widthPixels - w - dp(14)
        val y = dm.heightPixels - h - dp(14)
        return Rect(x, y, x + w, y + h)
    }

    private fun togglePin(pkg: String) {
        val clean = pkg.substringBefore(':')
        val pin = clean !in pinnedPkgs
        if (!keeper.setPin(clean, pin)) {
            toast("Не удалось изменить мини-окно")
            return
        }
        pinnedPkgs = if (pin) pinnedPkgs + clean else pinnedPkgs - clean
        keeper.syncService()
        lastSig = ""
        renderCards()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                val params = PictureInPictureParams.Builder().apply {
                    setAspectRatio(Rational(1, 1))
                    setActions(listOf(buildPipAction()))
                }.build()
                enterPictureInPictureMode(params)
            }.onFailure {
                toast("Не удалось свернуть в PiP")
            }
        } else {
            toast("PiP доступен на Android 8.0+")
        }
    }

    private fun buildPipAction(): android.app.RemoteAction {
        val reopen = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_rotate)
        return android.app.RemoteAction(
            icon, "Открыть", "Развернуть лаунчер", reopen,
        )
    }

    private fun openKeptInWindows() {
        val pkgs = keptPkgs.sorted()
        if (pkgs.isEmpty()) {
            toast("Нет приложений на keep")
            return
        }
        for (p in pkgs) launchAdjacent(p)
    }

    private fun loadApps() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val seen = HashSet<String>()
        val apps = ArrayList<AppEntry>()
        for (ri in resolved) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            if (!seen.add(pkg)) continue
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            val version = runCatching { pm.getPackageInfo(pkg, 0).versionName ?: "" }.getOrDefault("")
            val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull()
            apps.add(AppEntry(pkg, label, version, icon))
        }
        apps.sortBy { it.label.lowercase() }
        allApps = apps
    }

    @Suppress("DEPRECATION")
    private fun loadProcesses() {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val running = am.runningAppProcesses ?: emptyList()
        val list = ArrayList<ProcEntry>()
        for (p in running) {
            if (p.importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) continue
            list.add(ProcEntry(p.processName, p.pid, importanceName(p.importance)))
        }
        processes = list
    }

    @Suppress("DEPRECATION")
    private fun importanceName(importance: Int): String = when {
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND -> "background"
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "сервис"
        importance >= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        else -> "foreground"
    }

    private fun running(pkg: String): Boolean =
        processes.any { p -> p.packageName == pkg || p.packageName.startsWith("$pkg:") }

    private fun matches(a: AppEntry): Boolean {
        if (showKeptOnly && a.packageName !in keptPkgs) return false
        val q = query.trim().lowercase()
        return q.isEmpty() ||
            a.label.lowercase().contains(q) ||
            a.packageName.lowercase().contains(q)
    }

    private fun sig(): String {
        val sb = StringBuilder()
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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val KEEP_EXTRA = "keep_pkgs"
        const val PIN_EXTRA = "pin_pkgs"
        private const val REFRESH_MS = 2500L
        private const val MIN_CARD_WIDTH_DP = 150
    }
}