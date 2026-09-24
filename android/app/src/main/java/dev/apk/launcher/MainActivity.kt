package dev.apk.launcher

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
    private var query = ""
    private var showKeptOnly = false
    private var lastSig = ""

    private lateinit var statAppsNum: TextView
    private lateinit var statAppsLabel: TextView
    private lateinit var statAliveNum: TextView
    private lateinit var scrollContent: LinearLayout
    private lateinit var cardsBox: LinearLayout
    private lateinit var processPanel: LinearLayout

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
        loadApps()
        keptPkgs = keeper.list().toSet()
        loadProcesses()
        lastSig = ""
        render()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun buildUi() {
        val root = vBox()
        root.setBackgroundColor(Color.parseColor("#0d0d12"))
        setContentView(root)

        val header = hBox()
        header.setPadding(dp(16), dp(12), dp(16), dp(4))
        header.addView(tv("APK Launcher", 20f, Color.WHITE, Typeface.BOLD))

        val search = EditText(this)
        search.hint = "Поиск приложений…"
        search.setTextColor(Color.parseColor("#e8e8f0"))
        search.setHintTextColor(Color.parseColor("#66667a"))
        search.textSize = 15f
        search.isSingleLine = true
        search.setBackgroundResource(R.drawable.bg_search)
        search.setPadding(dp(12), 0, dp(12), 0)
        val slp = LinearLayout.LayoutParams(0, dp(40), 1f)
        slp.setMargins(dp(16), 0, 0, 0)
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
        }
        statAppsNum = statsNum(statApps)
        statAppsLabel = statsLabel(statApps, "приложений")
        keepbar.addView(statApps)

        val statAlive = statColumn()
        statAliveNum = statsNum(statAlive)
        statsLabel(statAlive, "живых процессов")
        keepbar.addView(statAlive)

        val statRefresh = statColumn()
        val refreshNum = statsNum(statRefresh)
        refreshNum.text = "⟳"
        refreshNum.setTextColor(Color.parseColor("#66667a"))
        statsLabel(statRefresh, "обновить")
        statRefresh.setOnClickListener {
            loadApps()
            keptPkgs = keeper.list().toSet()
            loadProcesses()
            lastSig = ""
            render()
        }
        keepbar.addView(statRefresh)
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
        keepBtn.text = if (kept) "держать" else "не держать"
        keepBtn.textSize = 12f
        keepBtn.isAllCaps = false
        keepBtn.minimumWidth = 0
        keepBtn.minimumHeight = 0
        keepBtn.setTextColor(if (kept) Color.parseColor("#4ade80") else Color.parseColor("#b9b9cc"))
        keepBtn.setBackgroundResource(if (kept) R.drawable.pill_keep_on else R.drawable.pill_keep_off)
        keepBtn.setOnClickListener { toggleKeep(app.packageName) }
        row.addView(keepBtn)
        return card
    }

    private fun updateStats() {
        statAppsNum.text = if (showKeptOnly) keptPkgs.size.toString() else allApps.size.toString()
        statAppsLabel.text = if (showKeptOnly) "на keep" else "приложений"
        statAliveNum.text = processes.count { p ->
            keptPkgs.any { k -> p.packageName == k || p.packageName.startsWith("$k:") }
        }.toString()
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
        val kept = keptPkgs.any { k -> p.packageName == k || p.packageName.startsWith("$k:") }
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

        row.addView(miniBtn("запустить") { launch(p.packageName) })
        row.addView(miniBtn(if (kept) "снять keep" else "keep") { toggleKeep(p.packageName) })
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
        private const val REFRESH_MS = 2500L
        private const val MIN_CARD_WIDTH_DP = 150
    }
}