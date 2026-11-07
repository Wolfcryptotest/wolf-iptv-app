package com.wolf.iptv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val io = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var x: Xtream? = null
    private var userInfo: JSONObject? = null

    // gezinti durumu
    private var tab = "live"
    private var cats: List<JSONObject> = emptyList()
    private var catIndex = 0
    private var items: List<JSONObject> = emptyList()
    private var shown: List<JSONObject> = emptyList()
    private var mode = "list"                 // list / eps
    private var series: JSONObject? = null
    private var seasons: LinkedHashMap<String, List<JSONObject>> = LinkedHashMap()
    private var season = ""
    private var eps: List<JSONObject> = emptyList()
    private var listPos = 0
    private var req = 0
    private val cache = HashMap<String, List<JSONObject>>()

    // görünümler
    private var catList: ListView? = null
    private lateinit var catAdapter: RowAdapter
    private lateinit var itemList: ListView
    private lateinit var itemAdapter: RowAdapter
    private lateinit var catButton: Button
    private lateinit var search: EditText
    private lateinit var searchRow: LinearLayout
    private lateinit var epBar: LinearLayout
    private lateinit var epTitle: TextView
    private lateinit var seasonButton: Button
    private lateinit var info: TextView
    private val tabButtons = HashMap<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.loggedIn) {
            x = prefs.xtream()
            showBrowse()
            io.execute {
                try {
                    val u = x!!.login()
                    ui.post { userInfo = u; updateAccount() }
                } catch (e: Exception) {
                    ui.post { toast(e.message ?: "Bağlantı hatası") }
                }
            }
        } else {
            showLogin()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (x != null && ::itemList.isInitialized) {
            val keepMode = mode
            showBrowse(keepState = true)
            if (keepMode == "eps") renderItems()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::itemAdapter.isInitialized) renderItems(keepScroll = true)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun wide(): Boolean = resources.configuration.screenWidthDp >= 600

    // ---------------------------------------------------------------- giriş
    private fun showLogin(error: String = "") {
        val scroll = ScrollView(this).apply { setBackgroundColor(C.BG); isFillViewport = true }
        val outer = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(18), dp(24), dp(18), dp(24)) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(C.PANEL, dp(16).toFloat())
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        outer.addView(card, lp(MATCH, WRAP).apply { width = minOf(dp(460), resources.displayMetrics.widthPixels - dp(36)) })
        scroll.addView(outer, lp(MATCH, MATCH))

        val title = text("Wolf IPTV", 26f, bold = true)
        val subtitle = text("Sunucu bilgilerini gir (Xtream Codes girişi)", 14f, C.MUTED)
        card.addView(title)
        card.addView(subtitle, lp(MATCH, WRAP).apply { bottomMargin = dp(16) })

        fun field(label: String, value: String, hint: String, pw: Boolean = false): EditText {
            card.addView(text(label, 13f, C.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(8) })
            val e = EditText(this).apply {
                setText(value)
                this.hint = hint
                setHintTextColor(Color.parseColor("#5C6275"))
                setTextColor(C.FG)
                setSingleLine(true)
                background = states(context, C.FIELD)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                inputType = if (pw) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            card.addView(e, lp(MATCH, WRAP).apply { topMargin = dp(4) })
            return e
        }

        val eServer = field("Sunucu URL", prefs.server, "http://sunucu-adresi.com:8080")
        val eUser = field("Kullanıcı adı", prefs.user, "")
        val ePass = field("Şifre", prefs.pass, "", pw = true)
        val status = text(error, 13.5f, if (error.isEmpty()) C.MUTED else C.RED)
        lateinit var go: Button
        go = button("Bağlan", C.ACC) {
            var server = eServer.text.toString().trim()
            var user = eUser.text.toString().trim()
            var pass = ePass.text.toString().trim()
            // tam M3U linki yapıştırıldıysa kullanıcı adı/şifreyi oradan al
            if (server.contains("username=") && server.contains("password=")) {
                val uri = android.net.Uri.parse(if (server.contains("://")) server else "http://$server")
                if (user.isEmpty()) user = uri.getQueryParameter("username") ?: ""
                if (pass.isEmpty()) pass = uri.getQueryParameter("password") ?: ""
            }
            server = Xtream.normServer(server)
            if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                status.setTextColor(C.RED); status.text = "Sunucu URL, kullanıcı adı ve şifre gerekli."
                return@button
            }
            status.setTextColor(C.MUTED); status.text = "Bağlanılıyor…"
            go.isEnabled = false
            val xt = Xtream(server, user, pass)
            io.execute {
                try {
                    val u = xt.login()
                    ui.post {
                        prefs.server = server; prefs.user = user; prefs.pass = pass
                        x = xt; userInfo = u; cache.clear()
                        showBrowse()
                    }
                } catch (e: Exception) {
                    ui.post { go.isEnabled = true; status.setTextColor(C.RED); status.text = e.message ?: "Bağlantı hatası" }
                }
            }
        }
        ePass.imeOptions = EditorInfo.IME_ACTION_GO
        ePass.setOnEditorActionListener { _, _, _ -> go.performClick(); true }
        card.addView(go, lp(MATCH, WRAP).apply { topMargin = dp(20) })
        card.addView(status, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        card.addView(text("Bilgiler sadece bu cihazda saklanır.", 12f, C.MUTED), lp(MATCH, WRAP).apply { topMargin = dp(8) })
        setContentView(scroll)
        (if (prefs.server.isEmpty()) eServer else if (prefs.user.isEmpty()) eUser else ePass).requestFocus()
    }

    // ---------------------------------------------------------------- ana ekran
    private lateinit var accText: TextView

    private fun showBrowse(keepState: Boolean = false) {
        if (!keepState) {
            tab = prefs.tab
            mode = "list"
        }
        Hold.x = x
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        // üst çubuk
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.PANEL)
            setPadding(dp(12), dp(6), dp(8), dp(6))
        }
        top.addView(text("Wolf ", 19f, bold = true))
        top.addView(text("IPTV", 19f, C.RED, bold = true))
        accText = text("", 12f, C.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        top.addView(accText, lp(0, WRAP, 1f).apply { leftMargin = dp(10) })
        top.addView(button("Çıkış", C.FIELD) { confirmLogout() }.apply { minHeight = dp(36); minimumHeight = dp(36) })
        root.addView(top, lp(MATCH, WRAP))
        updateAccount()

        // sekmeler
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(6), dp(6), 0) }
        tabButtons.clear()
        for ((key, label) in listOf("live" to "Canlı TV", "vod" to "Filmler", "series" to "Diziler")) {
            val b = button(label) { if (tab != key || mode == "eps") selectTab(key) }
            tabButtons[key] = b
            tabs.addView(b, lp(0, WRAP, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        }
        root.addView(tabs, lp(MATCH, WRAP))

        // arama + (dar ekranda) kategori seçici
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(4))
        }
        catButton = button("Kategori ▾") { pickCategory() }.apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        search = EditText(this).apply {
            hint = "Ara…"
            setHintTextColor(Color.parseColor("#5C6275"))
            setTextColor(C.FG)
            setSingleLine(true)
            background = states(context, C.FIELD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        if (!wide()) searchRow.addView(catButton, lp(0, WRAP, 1f).apply { rightMargin = dp(6) })
        searchRow.addView(search, lp(0, WRAP, 1f))
        root.addView(searchRow, lp(MATCH, WRAP))

        // dizi bölüm çubuğu
        epBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(4))
            visibility = View.GONE
        }
        epBar.addView(button("◀ Diziler") { closeSeries() })
        epTitle = text("", 15f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        epBar.addView(epTitle, lp(0, WRAP, 1f).apply { leftMargin = dp(10); rightMargin = dp(8) })
        seasonButton = button("Sezon ▾") { pickSeason() }
        epBar.addView(seasonButton)
        root.addView(epBar, lp(MATCH, WRAP))

        // gövde
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        catAdapter = RowAdapter(this)
        itemAdapter = RowAdapter(this)
        if (wide()) {
            val cl = makeList(catAdapter)
            cl.setOnItemClickListener { _, _, pos, _ -> loadCat(pos) }
            body.addView(cl, lp(0, MATCH, 1f))
            body.addView(View(this).apply { setBackgroundColor(C.LINE) }, lp(dp(1), MATCH))
            catList = cl
        } else {
            catList = null
        }
        itemList = makeList(itemAdapter)
        itemList.setOnItemClickListener { _, _, pos, _ -> activate(pos) }
        itemList.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = showInfo(pos)
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        })
        body.addView(itemList, lp(0, MATCH, if (wide()) 2f else 1f))
        root.addView(body, lp(MATCH, 0, 1f))

        info = text("", 12.5f, C.MUTED).apply {
            setPadding(dp(12), dp(6), dp(12), dp(8))
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setBackgroundColor(C.PANEL)
        }
        root.addView(info, lp(MATCH, WRAP))

        setContentView(root)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                ui.removeCallbacks(filterTask); ui.postDelayed(filterTask, 250)
            }
        })

        if (keepState && cats.isNotEmpty()) {
            markTab()
            catAdapter.rows = cats.map { RowAdapter.Row("", it.s("category_name")) }
            catAdapter.selected = catIndex
            catButton.text = cats.getOrNull(catIndex)?.s("category_name") ?: "Kategori ▾"
            if (mode == "eps") showEpBar(true)
            renderItems()
        } else {
            selectTab(tab)
        }
    }

    private val filterTask = Runnable { applyFilter() }

    private fun makeList(adapter: RowAdapter): ListView = ListView(this).apply {
        this.adapter = adapter
        divider = null
        dividerHeight = 0
        isFastScrollEnabled = true
        itemsCanFocus = false
        isFocusable = true
        choiceMode = AbsListView.CHOICE_MODE_NONE
        selector = states(context, Color.TRANSPARENT, dp(6).toFloat())
        setDrawSelectorOnTop(false)
        cacheColorHint = Color.TRANSPARENT
        setPadding(dp(4), dp(4), dp(4), dp(4))
        clipToPadding = false
    }

    private fun updateAccount() {
        if (!::accText.isInitialized) return
        val u = userInfo ?: run { accText.text = x?.user ?: ""; return }
        val exp = u.s("exp_date").toLongOrNull()
        val expS = if (exp != null && exp > 0) SimpleDateFormat("dd.MM.yyyy", Locale("tr")).format(Date(exp * 1000)) else "Sınırsız"
        accText.text = "${u.s("username")} • ${u.s("status")} • Bitiş: $expS"
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Çıkış")
            .setMessage("Hesaptan çıkılsın mı? (Sunucu ve kullanıcı adı hatırlanır, şifre silinir.)")
            .setPositiveButton("Çıkış yap") { _, _ -> prefs.logout(); x = null; cache.clear(); showLogin() }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun markTab() {
        for ((k, b) in tabButtons) b.isActivated = k == tab
    }

    // ---------------------------------------------------------------- veri yükleme
    private fun <T> load(job: () -> T, done: (T) -> Unit) {
        val token = ++req
        io.execute {
            try {
                val r = job()
                ui.post { if (token == req && !isFinishing) done(r) }
            } catch (e: Exception) {
                ui.post { if (token == req && !isFinishing) { setLoading(e.message ?: "Hata"); toast(e.message ?: "Hata") } }
            }
        }
    }

    private fun setLoading(msg: String) {
        itemAdapter.rows = listOf(RowAdapter.Row("", msg))
    }

    private fun cached(key: String, f: () -> List<JSONObject>): List<JSONObject> =
        synchronized(cache) { cache[key] } ?: f().also { synchronized(cache) { cache[key] = it } }

    private fun selectTab(t: String) {
        if (mode == "eps") closeSeries(silent = true)
        tab = t
        prefs.tab = t
        markTab()
        search.setText("")
        catAdapter.rows = listOf(RowAdapter.Row("", "Yükleniyor…"))
        setLoading("Yükleniyor…")
        val xt = x ?: return
        val action = when (t) { "live" -> "get_live_categories"; "vod" -> "get_vod_categories"; else -> "get_series_categories" }
        load({ cached(action) { xt.list(action) } }) { list ->
            val all = JSONObject().put("category_id", "").put("category_name", "★ Tümü")
            cats = listOf(all) + list
            catAdapter.rows = cats.map { RowAdapter.Row("", it.s("category_name")) }
            val saved = prefs.cat(t)
            var idx = cats.indexOfFirst { it.s("category_id") == saved }
            if (idx < 0) idx = if (cats.size > 1) 1 else 0
            loadCat(idx)
        }
    }

    private fun loadCat(i: Int) {
        val c = cats.getOrNull(i) ?: return
        if (mode == "eps") closeSeries(silent = true)
        catIndex = i
        catAdapter.selected = i
        catList?.setSelection(maxOf(0, i - 3))
        catButton.text = c.s("category_name")
        prefs.setCat(tab, c.s("category_id"))
        setLoading("Yükleniyor…")
        val xt = x ?: return
        val action = when (tab) { "live" -> "get_live_streams"; "vod" -> "get_vod_streams"; else -> "get_series" }
        val cid = c.s("category_id")
        val params = if (cid.isEmpty()) emptyMap() else mapOf("category_id" to cid)
        load({ cached("$action|$cid") { xt.list(action, params) } }) { list ->
            items = list
            applyFilter()
            itemList.setSelection(0)
        }
    }

    private fun pickCategory() {
        if (cats.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Kategori")
            .setItems(cats.map { it.s("category_name") }.toTypedArray()) { _, which -> loadCat(which) }
            .show()
    }

    private fun applyFilter() {
        if (mode == "eps") return
        val q = fold(search.text.toString().trim())
        shown = if (q.isEmpty()) items else items.filter { fold(it.s("name")).contains(q) }
        renderItems()
    }

    private fun playingKey(): String = PlayerState.key

    private fun keyOf(it: JSONObject): String = when {
        mode == "eps" -> "ep:" + it.s("id")
        tab == "live" -> "live:" + it.s("stream_id")
        tab == "vod" -> "vod:" + it.s("stream_id")
        else -> "ser:" + it.s("series_id")
    }

    private fun renderItems(keepScroll: Boolean = false) {
        val first = itemList.firstVisiblePosition
        val now = playingKey()
        val list = if (mode == "eps") eps else shown
        itemAdapter.rows = if (list.isEmpty()) listOf(RowAdapter.Row("", if (mode == "eps") "Bölüm bulunamadı." else "Bu listede içerik yok."))
        else list.map { it ->
            val playing = keyOf(it) == now
            when {
                mode == "eps" -> {
                    val inf = it.optJSONObject("info")
                    RowAdapter.Row(it.s("episode_num"), it.s("title"), inf?.s("duration") ?: "", playing)
                }
                tab == "live" -> RowAdapter.Row(it.s("num"), it.s("name"), "", playing)
                else -> {
                    val y = it.s("year").ifEmpty { it.s("releaseDate").take(4) }
                    val r = it.s("rating")
                    RowAdapter.Row("", it.s("name"), listOf(y, if (r.isNotEmpty() && r != "0") "★ $r" else "").filter { s -> s.isNotEmpty() }.joinToString(" · "), playing)
                }
            }
        }
        if (keepScroll) itemList.setSelection(first)
        info.text = if (mode == "eps") "${series?.s("name") ?: ""} • $season. Sezon • ${eps.size} bölüm"
        else "${shown.size} / ${items.size}  •  " + when (tab) {
            "live" -> "Kanala dokun: oynar • Oynatıcıda yukarı/aşağı kaydır: kanal değiştir"
            "vod" -> "Filme dokun: oynar"
            else -> "Diziye dokun: sezon ve bölümler"
        }
    }

    private fun showInfo(pos: Int) {
        val list = if (mode == "eps") eps else shown
        val it = list.getOrNull(pos) ?: return
        if (mode == "eps") {
            val inf = it.optJSONObject("info")
            info.text = listOf(it.s("title"), inf?.s("plot") ?: "").filter { s -> s.isNotEmpty() }.joinToString("\n")
        } else if (tab != "live") {
            info.text = listOf(it.s("name"), it.s("genre"), it.s("plot")).filter { s -> s.isNotEmpty() }.joinToString("\n")
        }
    }

    private fun activate(pos: Int) {
        if (mode == "eps") {
            if (pos < eps.size) play("ep", eps, pos)
            return
        }
        val it = shown.getOrNull(pos) ?: return
        when (tab) {
            "live" -> play("live", shown, pos)
            "vod" -> play("vod", shown, pos)
            else -> openSeries(it)
        }
    }

    private fun play(kind: String, list: List<JSONObject>, pos: Int) {
        Hold.x = x
        Hold.kind = kind
        Hold.list = list
        Hold.index = pos
        Hold.seriesName = series?.s("name") ?: ""
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ---------------------------------------------------------------- diziler (aynı ekran içinde)
    private fun showEpBar(on: Boolean) {
        epBar.visibility = if (on) View.VISIBLE else View.GONE
        searchRow.visibility = if (on) View.GONE else View.VISIBLE
        if (on) {
            epTitle.text = series?.s("name") ?: ""
            seasonButton.text = if (season.isEmpty()) "Sezon ▾" else "$season. Sezon ▾"
        }
    }

    private fun openSeries(s: JSONObject) {
        series = s
        mode = "eps"
        listPos = itemList.firstVisiblePosition
        seasons = LinkedHashMap()
        season = ""
        eps = emptyList()
        showEpBar(true)
        setLoading("Bölümler yükleniyor…")
        val xt = x ?: return
        val sid = s.s("series_id")
        load({ xt.obj("get_series_info", mapOf("series_id" to sid)) }) { d ->
            if (mode != "eps" || series !== s) return@load
            val map = LinkedHashMap<String, List<JSONObject>>()
            val e = d.opt("episodes")
            if (e is JSONObject) {
                val keys = e.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: 0 }
                for (k in keys) {
                    val arr = e.optJSONArray(k) ?: continue
                    map[k] = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
            } else if (e is org.json.JSONArray) {
                for (i in 0 until e.length()) {
                    val arr = e.optJSONArray(i) ?: continue
                    map[(i + 1).toString()] = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
            }
            seasons = map
            val playing = playingKey()
            val start = map.entries.firstOrNull { en -> en.value.any { "ep:" + it.s("id") == playing } }?.key
                ?: map.keys.firstOrNull() ?: ""
            showSeason(start)
            d.optJSONObject("info")?.s("plot")?.takeIf { it.isNotEmpty() }?.let { info.text = it }
        }
    }

    private fun showSeason(k: String) {
        season = k
        eps = seasons[k] ?: emptyList()
        seasonButton.text = if (k.isEmpty()) "Sezon ▾" else "$k. Sezon ▾"
        renderItems()
        val playing = playingKey()
        val idx = eps.indexOfFirst { "ep:" + it.s("id") == playing }
        itemList.setSelection(maxOf(0, idx))
        itemList.requestFocus()
    }

    private fun pickSeason() {
        if (seasons.isEmpty()) return
        val keys = seasons.keys.toList()
        AlertDialog.Builder(this)
            .setTitle("Sezon")
            .setItems(keys.map { "$it. Sezon (${seasons[it]?.size ?: 0} bölüm)" }.toTypedArray()) { _, w -> showSeason(keys[w]) }
            .show()
    }

    private fun closeSeries(silent: Boolean = false) {
        if (mode != "eps") return
        mode = "list"
        req++  // bekleyen bölüm isteğini yok say
        showEpBar(false)
        if (silent) return
        renderItems()
        itemList.setSelection(listPos)
        itemList.requestFocus()
    }

    // ---------------------------------------------------------------- tuşlar
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (mode == "eps") {
            closeSeries()
        } else if (catList != null && itemList.hasFocus()) {
            catList?.requestFocus()
        } else {
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> { cycleTab(-1); return true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { cycleTab(1); return true }
            KeyEvent.KEYCODE_BUTTON_B -> { onBackPressed(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> { if (catList == null) pickCategory() else catList?.requestFocus(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun cycleTab(d: Int) {
        val order = listOf("live", "vod", "series")
        val i = (order.indexOf(tab) + d + order.size) % order.size
        selectTab(order[i])
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }
}

/** Şu an oynayan içerik (listede ▶ ile göstermek için). */
object PlayerState {
    var key: String = ""
}
