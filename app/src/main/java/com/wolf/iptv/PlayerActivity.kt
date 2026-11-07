package com.wolf.iptv

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.abs

class PlayerActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private lateinit var pv: PlayerView
    private lateinit var osd: LinearLayout
    private lateinit var osdTitle: TextView
    private lateinit var osdEpg: TextView
    private lateinit var msg: TextView
    private lateinit var spin: ProgressBar
    private lateinit var bar: LinearLayout

    private val x: Xtream? get() = Hold.x
    private var kind = "live"
    private var list: List<JSONObject> = emptyList()
    private var index = 0
    private var retried = false
    private var url = ""
    private var epgToken = 0
    private lateinit var favBtn: android.widget.Button
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        kind = Hold.kind
        list = Hold.list
        index = Hold.index
        if (x == null || list.isEmpty()) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        pv = PlayerView(this).apply {
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowFastForwardButton(kind != "live")
            setShowRewindButton(kind != "live")
            controllerShowTimeoutMs = 3500
            keepScreenOn = true
        }
        root.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))

        spin = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(spin, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        // üstte kanal adı + yayın akışı
        osd = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(26))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#CC000000"), Color.TRANSPARENT)
            )
        }
        osdTitle = text("", 20f, bold = true)
        osdEpg = text("", 13.5f, Color.parseColor("#CFD3E0"))
        osd.addView(osdTitle)
        osd.addView(osdEpg)
        root.addView(osd, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // önceki / sonraki (kanal veya bölüm)
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        favBtn = button("☆ Favori", Color.parseColor("#AA1E2029")) { toggleFavNow() }
        bar.addView(favBtn, lp(WRAP, WRAP).apply { rightMargin = dp(10) })
        if (kind != "vod") {
            val prevLabel = if (kind == "live") "◀◀ Önceki kanal" else "◀◀ Önceki bölüm"
            val nextLabel = if (kind == "live") "Sonraki kanal ▶▶" else "Sonraki bölüm ▶▶"
            bar.addView(button(prevLabel, Color.parseColor("#AA1E2029")) { zap(-1) }, lp(WRAP, WRAP).apply { rightMargin = dp(10) })
            bar.addView(button(nextLabel, Color.parseColor("#AA1E2029")) { zap(1) })
        }
        root.addView(bar, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = dp(6); rightMargin = dp(6) })

        msg = text("", 15f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = round(Color.parseColor("#CC000000"), dp(10).toFloat())
            visibility = View.GONE
        }
        root.addView(msg, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER or Gravity.BOTTOM).apply { bottomMargin = dp(90); leftMargin = dp(20); rightMargin = dp(20) })

        setContentView(root)
        hideSystemUi()

        pv.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            bar.visibility = v
            if (v == View.VISIBLE) showOsd(false) else hideSystemUi()
        })

        // dokunmatik: yukarı/aşağı kaydır = kanal/bölüm değiştir
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || kind == "vod") return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (abs(dy) > dp(80) && abs(dy) > abs(dx) * 1.5f && abs(vy) > 600) {
                    zap(if (dy < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        pv.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); false }

        createPlayer()
        startCurrent()
    }

    private fun createPlayer() {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(25000)
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                spin.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY) msg.visibility = View.GONE
                if (state == Player.STATE_ENDED) {
                    if (kind == "ep") {
                        showMsg("Sonraki bölüm…", 2000)
                        ui.postDelayed({ zap(1, wrap = false) }, 1500)
                    } else if (kind == "live") {
                        ui.postDelayed({ play(url) }, 1500)   // canlı yayın koptu: yeniden bağlan
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                spin.visibility = View.GONE
                if (kind == "live" && !retried) {
                    retried = true
                    val alt = if (url.endsWith(".ts")) url.removeSuffix(".ts") + ".m3u8" else url.removeSuffix(".m3u8") + ".ts"
                    showMsg("Diğer formatla deneniyor…", 2500)
                    play(alt)
                } else {
                    showMsg("Yayın açılamadı.\nKanal kapalı olabilir ya da hesap şu an başka bir cihazda kullanılıyor.", 0)
                }
            }
        })
        pv.player = p
        player = p
    }

    private fun current(): JSONObject = list[index]

    private fun titleOf(o: JSONObject): String = when (kind) {
        "ep" -> o.s("title").ifEmpty { "${Hold.seriesName} ${o.s("episode_num")}. Bölüm" }
        else -> o.s("name")
    }

    private fun startCurrent() {
        val o = current()
        val xt = x ?: return
        retried = false
        val u = when (kind) {
            "live" -> xt.liveUrl(o.s("stream_id"), Prefs(this).liveExt)
            "vod" -> xt.vodUrl(o.s("stream_id"), o.s("container_extension"))
            else -> xt.seriesUrl(o.s("id"), o.s("container_extension"))
        }
        PlayerState.key = when (kind) {
            "live" -> "live:" + o.s("stream_id")
            "vod" -> "vod:" + o.s("stream_id")
            else -> "ep:" + o.s("id")
        }
        osdTitle.text = titleOf(o)
        osdEpg.text = ""
        // son izlenenlere ekle
        when (kind) {
            "live", "vod" -> prefs.addRecent(kind, o)
            else -> Hold.series?.let { sr ->
                val rec = JSONObject(sr.toString())
                rec.put("_ep_id", o.s("id"))
                rec.put("_season", Hold.season)
                rec.put("_ep_title", titleOf(o))
                prefs.addRecent("series", rec)
            }
        }
        updateFavBtn()
        showOsd(true)
        play(u)
        if (kind == "live") loadEpg(o)
    }

    private fun play(u: String) {
        url = u
        val p = player ?: return
        msg.visibility = View.GONE
        val item = MediaItem.Builder()
            .setUri(u)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(titleOf(current())).build())
            .build()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    private fun favTarget(): Pair<String, JSONObject>? = when (kind) {
        "live", "vod" -> kind to current()
        else -> Hold.series?.let { "series" to it }
    }

    private fun updateFavBtn() {
        if (!::favBtn.isInitialized) return
        val t = favTarget()
        favBtn.visibility = if (t == null) View.GONE else View.VISIBLE
        favBtn.text = if (t != null && prefs.isFav(t.first, t.second)) "★ Favoride" else "☆ Favori"
    }

    private fun toggleFavNow() {
        val t = favTarget() ?: return
        val added = prefs.toggleFav(t.first, t.second)
        showMsg(if (added) "★ Favorilere eklendi" else "Favorilerden çıkarıldı", 1800)
        updateFavBtn()
    }

    private fun loadEpg(o: JSONObject) {
        val xt = x ?: return
        val token = ++epgToken
        val sid = o.s("stream_id")
        io.execute {
            try {
                val d = xt.obj("get_short_epg", mapOf("stream_id" to sid, "limit" to "3"))
                val arr = d.optJSONArray("epg_listings")
                val lines = ArrayList<String>()
                if (arr != null) for (i in 0 until minOf(3, arr.length())) {
                    val e = arr.optJSONObject(i) ?: continue
                    val st = e.s("start").let { if (it.length >= 16) it.substring(11, 16) else "" }
                    val en = e.s("end").let { if (it.length >= 16) it.substring(11, 16) else "" }
                    lines.add((if (st.isNotEmpty()) "$st-$en  " else "") + b64(e.s("title")))
                }
                ui.post { if (token == epgToken && !isFinishing) { osdEpg.text = lines.joinToString("\n"); showOsd(true) } }
            } catch (e: Exception) {
                // yayın akışı yoksa sessizce geç
            }
        }
    }

    private val hideOsdTask = Runnable { osd.animate().alpha(0f).setDuration(300).start() }

    private fun showOsd(autoHide: Boolean) {
        osd.animate().cancel()
        osd.alpha = 1f
        ui.removeCallbacks(hideOsdTask)
        ui.postDelayed(hideOsdTask, if (autoHide) 5000 else 3500)
    }

    private val hideMsgTask = Runnable { msg.visibility = View.GONE }

    private fun showMsg(t: String, ms: Long) {
        msg.text = t
        msg.visibility = View.VISIBLE
        ui.removeCallbacks(hideMsgTask)
        if (ms > 0) ui.postDelayed(hideMsgTask, ms)
    }

    private fun zap(d: Int, wrap: Boolean = true) {
        if (kind == "vod" || list.isEmpty()) return
        var n = index + d
        if (!wrap && (n < 0 || n >= list.size)) { showMsg("Son bölüm bitti.", 3000); return }
        n = ((n % list.size) + list.size) % list.size
        index = n
        Hold.index = n
        startCurrent()
    }

    private fun seek(ms: Long) {
        val p = player ?: return
        p.seekTo(maxOf(0L, p.currentPosition + ms))
        pv.showController()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val ctl = pv.isControllerFullyVisible
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> if (kind == "live" && !ctl) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (kind == "live" && !ctl) { zap(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> if (kind != "live" && !ctl) { seek(-10000); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (kind != "live" && !ctl) { seek(10000); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                    if (!ctl) { pv.showController(); showOsd(true); return true }
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    pv.showController()
                    return true
                }
                KeyEvent.KEYCODE_BUTTON_B -> { finish(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        if (player == null && list.isNotEmpty() && x != null) {
            createPlayer()
            startCurrent()
        }
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        if (::pv.isInitialized) pv.player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }
}
