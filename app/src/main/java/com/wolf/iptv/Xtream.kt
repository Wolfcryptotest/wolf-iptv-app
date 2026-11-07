package com.wolf.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val UA = "VLC/3.0.20 LibVLC/3.0.20"

/** Xtream Codes API istemcisi (player_api.php). */
class Xtream(server: String, val user: String, val pass: String) {

    val server: String = normServer(server)

    private fun q(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun p(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun raw(action: String?, params: Map<String, String> = emptyMap()): String {
        val sb = StringBuilder()
        sb.append(server).append("/player_api.php?username=").append(q(user)).append("&password=").append(q(pass))
        if (action != null) sb.append("&action=").append(action)
        for ((k, v) in params) sb.append('&').append(k).append('=').append(q(v))
        val c = URL(sb.toString()).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 15000
        c.readTimeout = 40000
        c.instanceFollowRedirects = true
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw Exception("Sunucu erişimi reddetti (HTTP $code). Kullanıcı adı/şifreyi kontrol et.")
            if (code >= 400) throw Exception("Sunucu hata döndürdü (HTTP $code)")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun list(action: String, params: Map<String, String> = emptyMap()): List<JSONObject> {
        val t = raw(action, params).trim()
        if (!t.startsWith("[")) return emptyList()
        val a = JSONArray(t)
        val out = ArrayList<JSONObject>(a.length())
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    fun obj(action: String?, params: Map<String, String> = emptyMap()): JSONObject {
        val t = raw(action, params).trim()
        return if (t.startsWith("{")) JSONObject(t) else JSONObject()
    }

    fun login(): JSONObject {
        val ui = obj(null).optJSONObject("user_info")
            ?: throw Exception("Sunucudan beklenmeyen yanıt geldi. Adresi kontrol et.")
        if (ui.optString("auth") != "1") throw Exception("Giriş başarısız: kullanıcı adı/şifre hatalı ya da hesap aktif değil.")
        return ui
    }

    fun liveUrl(id: String, ext: String = "ts") = "$server/live/${p(user)}/${p(pass)}/$id.$ext"
    fun vodUrl(id: String, ext: String) = "$server/movie/${p(user)}/${p(pass)}/$id.${ext.ifEmpty { "mp4" }}"
    fun seriesUrl(id: String, ext: String) = "$server/series/${p(user)}/${p(pass)}/$id.${ext.ifEmpty { "mp4" }}"

    companion object {
        fun normServer(input: String): String {
            var s = input.trim().trim('.', ',', ';', ' ')
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return try {
                val u = URL(s)
                val port = if (u.port > 0) ":${u.port}" else ""
                "${u.protocol}://${u.host.trim('.')}$port"
            } catch (e: Exception) {
                s.trimEnd('/', '.')
            }
        }
    }
}

/** Kayıtlı ayarlar. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("wolf", Context.MODE_PRIVATE)
    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v).apply()
    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v).apply()
    var pass: String
        get() = sp.getString("pass", "") ?: ""
        set(v) = sp.edit().putString("pass", v).apply()
    var tab: String
        get() = sp.getString("tab", "live") ?: "live"
        set(v) = sp.edit().putString("tab", v).apply()
    var liveExt: String
        get() = sp.getString("liveExt", "ts") ?: "ts"
        set(v) = sp.edit().putString("liveExt", v).apply()

    fun cat(tab: String): String? = sp.getString("cat_$tab", null)
    fun setCat(tab: String, id: String) = sp.edit().putString("cat_$tab", id).apply()

    // ---- favoriler / son izlenenler (tür başına JSON dizisi)
    private fun read(key: String): MutableList<JSONObject> {
        val t = sp.getString(key, "[]") ?: "[]"
        return try {
            val a = JSONArray(t)
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(key: String, list: List<JSONObject>) {
        val a = JSONArray()
        list.forEach { a.put(it) }
        sp.edit().putString(key, a.toString()).apply()
    }

    fun favs(kind: String): MutableList<JSONObject> = read("fav_$kind")
    fun recent(kind: String): MutableList<JSONObject> = read("recent_$kind")

    fun isFav(kind: String, o: JSONObject): Boolean {
        val id = idOf(kind, o)
        return favs(kind).any { idOf(kind, it) == id }
    }

    /** true = eklendi, false = çıkarıldı */
    fun toggleFav(kind: String, o: JSONObject): Boolean {
        val list = favs(kind)
        val id = idOf(kind, o)
        val had = list.removeAll { idOf(kind, it) == id }
        if (!had) list.add(0, slim(o, keepEp = false))
        write("fav_$kind", list)
        return !had
    }

    fun addRecent(kind: String, o: JSONObject) {
        val list = recent(kind)
        val id = idOf(kind, o)
        list.removeAll { idOf(kind, it) == id }
        list.add(0, slim(o, keepEp = true))
        while (list.size > 40) list.removeAt(list.size - 1)
        write("recent_$kind", list)
    }

    fun removeRecent(kind: String, o: JSONObject) {
        val list = recent(kind)
        val id = idOf(kind, o)
        list.removeAll { idOf(kind, it) == id }
        write("recent_$kind", list)
    }

    // ---- ebeveyn kilidi
    var parentMode: String                      // gizle / kilit / kapali
        get() = sp.getString("parentMode", "gizle") ?: "gizle"
        set(v) = sp.edit().putString("parentMode", v).apply()
    var pinHash: String
        get() = sp.getString("pinHash", "") ?: ""
        set(v) = sp.edit().putString("pinHash", v).apply()
    val salt: String
        get() {
            var t = sp.getString("salt", "") ?: ""
            if (t.isEmpty()) {
                t = java.util.UUID.randomUUID().toString()
                sp.edit().putString("salt", t).apply()
            }
            return t
        }

    fun hash(pin: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest("$salt|$pin".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    val loggedIn: Boolean get() = server.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()
    fun xtream(): Xtream = Xtream(server, user, pass)
    fun logout() = sp.edit().remove("pass").apply()
}

fun idOf(kind: String, o: JSONObject): String = if (kind == "series") o.s("series_id") else o.s("stream_id")

private val KEEP = listOf("stream_id", "series_id", "name", "num", "stream_icon", "cover", "container_extension",
    "year", "rating", "releaseDate", "genre", "plot", "category_id")

fun slim(o: JSONObject, keepEp: Boolean): JSONObject {
    val d = JSONObject()
    for (k in KEEP) {
        val v = o.s(k)
        if (v.isNotEmpty()) d.put(k, if (k == "plot") v.take(300) else v)
    }
    if (keepEp) for (k in listOf("_ep_id", "_season", "_ep_title")) {
        val v = o.s(k)
        if (v.isNotEmpty()) d.put(k, v)
    }
    return d
}

private val ADULT = listOf("+18", "18+", "+ 18", "18 +", "xxx", "adult", "yetiskin", "porn", "erotik", "erotic",
    "playboy", "hustler", "dorcel", "brazzers", "penthouse", "redlight", "for adults")

fun isAdultName(name: String): Boolean {
    val f = fold(name)
    return ADULT.any { f.contains(it) }
}

/** Bu oturumda +18 kilidi açıldı mı (uygulama kapanınca kilitlenir). */
object Lock {
    var unlocked = false
}

/** Ekranlar arasında büyük listeleri taşımak için. */
object Hold {
    var x: Xtream? = null
    var kind: String = "live"          // live / vod / ep
    var list: List<JSONObject> = emptyList()
    var index: Int = 0
    var seriesName: String = ""
    var series: JSONObject? = null
    var season: String = ""
}

fun JSONObject.s(key: String): String {
    val v = opt(key)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

fun b64(s: String): String = try {
    String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
} catch (e: Exception) {
    s
}

fun fold(s: String): String {
    val lower = s.lowercase(java.util.Locale("tr")).replace('ı', 'i')
    return java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}
