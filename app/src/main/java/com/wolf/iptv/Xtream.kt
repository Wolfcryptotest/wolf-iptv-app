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

    val loggedIn: Boolean get() = server.isNotEmpty() && user.isNotEmpty() && pass.isNotEmpty()
    fun xtream(): Xtream = Xtream(server, user, pass)
    fun logout() = sp.edit().remove("pass").apply()
}

/** Ekranlar arasında büyük listeleri taşımak için. */
object Hold {
    var x: Xtream? = null
    var kind: String = "live"          // live / vod / ep
    var list: List<JSONObject> = emptyList()
    var index: Int = 0
    var seriesName: String = ""
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
