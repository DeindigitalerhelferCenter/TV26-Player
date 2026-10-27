package com.tv26.player.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.TimeZone

/**
 * Client für MAC-Portale (Stalker / Ministra Middleware).
 * Die App meldet sich wie eine MAG-Box mit der MAC-Adresse an.
 */
class Stalker(p: Profile) {
    private val mac = findMac(p.user) ?: p.user.trim().uppercase()
    private val portal = cleanUrl(p.url).trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    @Volatile private var api: String? = null
    @Volatile private var token = ""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun hash(alg: String, s: String) =
        MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02X".format(it) }

    private val serial get() = hash("MD5", mac).take(13)
    private val deviceId get() = hash("SHA-256", mac)

    /** Mögliche API-Adressen, je nachdem was der Nutzer eingetragen hat (…/c/, …/stalker_portal, …/portal.php). */
    private fun candidates(): List<String> {
        if (portal.endsWith(".php")) return listOf(portal)
        val base = portal.removeSuffix("/c").removeSuffix("/stalker_portal")
        return listOf(
            "$base/portal.php",
            "$base/stalker_portal/server/load.php",
            "$base/server/load.php",
        )
    }

    private fun headers() = mapOf(
        "User-Agent" to UA,
        "X-User-Agent" to "Model: MAG250; Link: WiFi",
        "Cookie" to "mac=${enc(mac)}; stb_lang=en; timezone=${enc(TimeZone.getDefault().id)}",
        "Referer" to "$portal/",
        "Accept" to "*/*",
    ) + (if (token.isNotEmpty()) mapOf("Authorization" to "Bearer $token") else emptyMap())

    private fun call(apiUrl: String, query: String): JSONObject {
        val t = Net.text("$apiUrl?$query&JsHttpRequest=1-xml", headers(), readTimeout = 15_000).trim()
        val action = Regex("action=([a-z_]+)").find(query)?.groupValues?.get(1)
        android.util.Log.d("TV26", "portal $action -> ${t.length} Zeichen")
        if (t.contains("Authorization failed", ignoreCase = true)) {
            android.util.Log.w("TV26", "portal $action: Authorization failed")
            throw AuthExpired()
        }
        if (!t.startsWith("{")) {
            android.util.Log.w("TV26", "portal $action: keine JSON-Antwort: ${t.take(120)}")
            throw IOException(t.take(120).ifBlank { "Leere Antwort" })
        }
        return JSONObject(t)
    }

    private class AuthExpired : IOException("Authorization failed")

    @Volatile private var lastLogin = 0L

    @Synchronized
    fun login() {
        var last: Exception? = null
        lastLogin = System.currentTimeMillis()
        android.util.Log.i("TV26", "portal login (handshake)")
        // Jede Adresse bei einem Netzwerkfehler einmal wiederholen (kurze Aussetzer); "nicht gefunden" nicht
        val tries = candidates().flatMap { listOf(it, it) }
        var skip: String? = null
        for (c in tries) {
            if (c == skip) continue
            try {
                token = ""
                val tk = call(c, "type=stb&action=handshake&token=").optJSONObject("js")?.s("token").orEmpty()
                if (tk.isEmpty()) { skip = c; continue }
                token = tk
                api = c
                val prof = runCatching {
                    call(
                        c,
                        "type=stb&action=get_profile&hd=1&ver=ImageDescription%3A%200.2.18-r23-250%3B&num_banks=2" +
                            "&sn=$serial&stb_type=MAG250&image_version=218&video_out=hdmi&device_id=$deviceId" +
                            "&device_id2=$deviceId&signature=&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0" +
                            "&timestamp=${System.currentTimeMillis() / 1000}&api_signature=262&prehash=",
                    ).optJSONObject("js")
                }.getOrNull()
                if (prof?.s("blocked") == "1") throw IOException(tx("Konto gesperrt"))
                return
            } catch (e: Exception) {
                android.util.Log.w("TV26", "portal login an $c fehlgeschlagen: $e")
                last = e
                // 404 / gesperrt: diese Adresse nicht noch einmal versuchen. Sonst kurz warten und wiederholen.
                if ("HTTP 404" in (e.message ?: "") || e.message == tx("Konto gesperrt")) skip = c else Thread.sleep(800)
            }
        }
        throw IOException(tx("MAC-Login fehlgeschlagen. Bitte Portal-Adresse und MAC prüfen.") + (last?.message?.let { "\n($it)" } ?: ""))
    }

    /**
     * Ruft die API auf. Neu angemeldet wird NUR, wenn das Portal "Authorization failed" meldet,
     * und höchstens alle 20 Sekunden. Eine neue Anmeldung bekommt einen neuen Token, und viele
     * Portale beenden dann den laufenden Stream – darum so selten wie möglich.
     */
    private fun js(query: String): Any? {
        if (api == null) login()
        return try {
            call(api!!, query).opt("js")
        } catch (e: AuthExpired) {
            relogin()
            call(api!!, query).opt("js")
        }
    }

    @Synchronized
    private fun relogin() {
        // Hat ein anderer Aufruf gerade erst neu angemeldet? Dann den neuen Token einfach mitbenutzen.
        if (System.currentTimeMillis() - lastLogin < 20_000) return
        login()
    }

    /** "Ich bin noch da" an das Portal, wie eine MAG-Box beim Fernsehen. */
    fun keepAlive() {
        val a = api ?: return
        runCatching { call(a, "type=watchdog&action=get_events&cur_play_type=1&event_active_id=0&init=0") }
    }

    /**
     * Liest die "js"-Antwort direkt aus dem Netzwerk, Stück für Stück (spart viel Speicher bei großen Listen,
     * wichtig für schwache Sticks). Gibt null zurück, wenn etwas nicht passt – dann wird der normale Weg genutzt.
     */
    private fun <T> streamJs(query: String, read: (JsonReader) -> T): T? {
        return try {
            if (api == null) login()
            Net.open("$api?$query&JsHttpRequest=1-xml", headers(), readTimeout = 30_000).use { input ->
                val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
                var result: T? = null
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "js" && r.peek() != JsonToken.NULL) result = read(r) else r.skipValue()
                }
                result
            }
        } catch (e: Exception) {
            android.util.Log.w("TV26", "Stream-Lesen fehlgeschlagen, normaler Weg: $e")
            null
        }
    }

    /** Wert als Text lesen, egal ob Zahl, Text, true/false oder null. */
    private fun JsonReader.str(): String = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        JsonToken.NULL -> { nextNull(); "" }
        else -> { skipValue(); "" }
    }

    fun live(): List<Item> {
        val g = js("type=itv&action=get_genres") as? JSONArray ?: JSONArray()
        val genres = (0 until g.length()).associate { g.getJSONObject(it).let { o -> o.s("id") to o.s("title") } }
        // Sparsam: Kanalliste direkt aus dem Netzwerk lesen
        streamJs("type=itv&action=get_all_channels") { r -> readChannels(r, genres) }
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        val data = (js("type=itv&action=get_all_channels") as? JSONObject)?.optJSONArray("data") ?: JSONArray()
        return List(data.length()) { i ->
            val o = data.getJSONObject(i)
            val id = o.s("id")
            Item(
                id = "s:l:$id", name = o.s("name"), url = "stalker:itv:" + o.s("cmd"),
                logo = o.s("logo").takeIf { it.startsWith("http") } ?: "",
                group = genres[o.s("tv_genre_id")] ?: tx("Andere"), kind = Kind.LIVE,
                epgId = o.s("xmltv_id"), streamId = id,
            )
        }
    }

    private fun readChannels(r: JsonReader, genres: Map<String, String>): List<Item> {
        val out = ArrayList<Item>()
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return out }
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "data" || r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); continue }
            r.beginArray()
            while (r.hasNext()) {
                var id = ""; var name = ""; var cmd = ""; var logo = ""; var genre = ""; var xml = ""
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "id" -> id = r.str()
                        "name" -> name = r.str()
                        "cmd" -> cmd = r.str()
                        "logo" -> logo = r.str()
                        "tv_genre_id" -> genre = r.str()
                        "xmltv_id" -> xml = r.str()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                out += Item(
                    id = "s:l:$id", name = name, url = "stalker:itv:$cmd",
                    logo = logo.takeIf { it.startsWith("http") } ?: "",
                    group = genres[genre] ?: tx("Andere"), kind = Kind.LIVE, epgId = xml, streamId = id,
                )
            }
            r.endArray()
        }
        r.endObject()
        return out
    }

    private fun readEpg(r: JsonReader): Map<String, List<Program>> {
        val out = HashMap<String, List<Program>>()
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return out }
        r.beginObject()
        while (r.hasNext()) {
            if (r.nextName() != "data" || r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
            r.beginObject()
            while (r.hasNext()) {
                val ch = r.nextName()
                if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); continue }
                val list = ArrayList<Program>()
                r.beginArray()
                while (r.hasNext()) {
                    var n = ""; var d = ""; var s = 0L; var e = 0L
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "name" -> n = r.str()
                            "descr" -> d = r.str()
                            "start_timestamp" -> s = r.str().toLongOrNull() ?: 0
                            "stop_timestamp" -> e = r.str().toLongOrNull() ?: 0
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (e > s) list += Program(s * 1000, e * 1000, n, d)
                }
                r.endArray()
                out[ch] = list.sortedBy { it.start }
            }
            r.endObject()
        }
        r.endObject()
        return out
    }

    private val host = Regex("^https?://[^/]+").find(portal)?.value ?: portal

    private fun image(o: JSONObject): String {
        val u = o.s("screenshot_uri").ifBlank { o.s("cover_big") }.ifBlank { o.s("logo") }
        return when {
            u.startsWith("http") -> u
            u.startsWith("/") -> host + u
            else -> ""
        }
    }

    /** Kategorien für Filme ("vod") oder Serien ("series"). */
    fun vodCategories(type: String): List<Pair<String, String>> {
        val a = js("type=$type&action=get_categories") as? JSONArray ?: return emptyList()
        return List(a.length()) { a.getJSONObject(it).let { o -> o.s("id") to o.s("title") } }
            .filter { it.first.isNotEmpty() && it.first != "*" }
    }

    /** Eine Seite einer Kategorie. Gibt (Seitenzahl gesamt, Einträge) zurück. */
    fun vodPage(type: String, catId: String, group: String, page: Int): Pair<Int, List<Item>> {
        val js = js(
            "type=$type&action=get_ordered_list&category=${enc(catId)}&genre=*&p=$page&sortby=added" +
                "&fav=0&hd=0&not_ended=0&abc=*&years=*",
        ) as? JSONObject ?: return 0 to emptyList()
        val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
        val pages = (js.optInt("total_items") + per - 1) / per
        val data = js.optJSONArray("data") ?: JSONArray()
        return pages to List(data.length()) {
            val o = data.getJSONObject(it)
            val id = o.s("id")
            val series = type == "series" || o.s("is_series") == "1"
            Item(
                id = "s:${if (series) "s" else "m"}:$type:$id", name = o.s("name"),
                url = if (series) "" else "stalker:vod:" + o.s("cmd"),
                logo = image(o), group = group, kind = if (series) Kind.SERIES else Kind.MOVIE,
                plot = o.s("description"), rating = o.s("rating_imdb").ifBlank { o.s("rating_kinopoisk") },
                year = o.s("year"), streamId = "$type:$id",
            )
        }
    }

    /** Staffeln und Folgen einer Serie. */
    fun episodes(streamId: String): List<Episode> {
        val type = streamId.substringBefore(':')
        val id = streamId.substringAfter(':')
        val out = ArrayList<Episode>()
        var page = 1
        var pages = 1
        while (page <= pages && page <= 10) {
            val js = js("type=$type&action=get_ordered_list&movie_id=${enc(id)}&season_id=0&episode_id=0&p=$page") as? JSONObject ?: break
            val per = js.optInt("max_page_items", 14).coerceAtLeast(1)
            pages = (js.optInt("total_items") + per - 1) / per
            val data = js.optJSONArray("data") ?: break
            for (i in 0 until data.length()) {
                val s = data.getJSONObject(i)
                val seasonNum = Regex("\\d+").find(s.s("name"))?.value?.toIntOrNull() ?: (i + 1 + (page - 1) * per)
                val nums = s.optJSONArray("series")
                if (nums != null && nums.length() > 0) {
                    // Ältere Portale: Staffel mit Liste der Folgen-Nummern
                    for (k in 0 until nums.length()) {
                        val n = nums.optInt(k)
                        out += Episode(
                            "${s.s("id")}:$n", tx("Folge %s", n), seasonNum, n,
                            "stalker:vod:${s.s("cmd")}|$n", s.s("description"), image(s), "",
                        )
                    }
                } else {
                    // Neuere Portale: Folgen einer Staffel einzeln abfragen
                    val ep = js("type=$type&action=get_ordered_list&movie_id=${enc(id)}&season_id=${enc(s.s("id"))}&episode_id=0") as? JSONObject
                    val list = ep?.optJSONArray("data") ?: continue
                    for (k in 0 until list.length()) {
                        val e = list.getJSONObject(k)
                        val n = e.optInt("series_number", k + 1)
                        out += Episode(
                            e.s("id"), e.s("name").ifBlank { tx("Folge %s", n) }, seasonNum, n,
                            "stalker:vod:${e.s("cmd")}|", e.s("description"), image(e).ifBlank { image(s) }, "",
                        )
                    }
                }
            }
            page++
        }
        return out.sortedWith(compareBy({ it.season }, { it.num }))
    }

    /** Erzeugt den echten Stream-Link (die Links vom Portal sind nur kurz gültig). */
    fun createLink(type: String, cmd: String, series: String = ""): String {
        val js = js("type=$type&action=create_link&cmd=${enc(cmd)}&series=$series&forced_storage=undefined&disable_ad=0&download=0") as? JSONObject
        val out = js?.s("cmd").orEmpty().ifBlank { cmd }
        val url = out.trim().substringAfterLast(' ')
        if (!url.startsWith("http") && !url.startsWith("rtmp") && !url.startsWith("udp")) {
            throw IOException(tx("Link konnte nicht erstellt werden"))
        }
        return url
    }

    /** TV-Programm für alle Kanäle auf einmal: Kanal-ID -> Sendungen. */
    fun epgAll(hours: Int = 48): Map<String, List<Program>> {
        // Sparsam: TV-Programm direkt aus dem Netzwerk lesen
        streamJs("type=itv&action=get_epg_info&period=$hours") { r -> readEpg(r) }?.takeIf { it.isNotEmpty() }?.let { return it }
        val data = (js("type=itv&action=get_epg_info&period=$hours") as? JSONObject)?.optJSONObject("data") ?: return emptyMap()
        val out = HashMap<String, List<Program>>()
        data.keys().forEach { ch -> data.optJSONArray(ch)?.let { out[ch] = programs(it) } }
        return out
    }

    fun shortEpg(chId: String): List<Program> =
        (js("type=itv&action=get_short_epg&ch_id=$chId&size=20") as? JSONArray)?.let { programs(it) } ?: emptyList()

    private fun programs(a: JSONArray): List<Program> = List(a.length()) {
        val o = a.getJSONObject(it)
        Program(o.optLong("start_timestamp") * 1000, o.optLong("stop_timestamp") * 1000, o.s("name"), o.s("descr"))
    }.filter { it.stop > it.start }.sortedBy { it.start }

    companion object {
        const val UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        private val MAC_RE = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        fun validMac(m: String) = MAC_RE.matches(m.trim())

        /** Header für die Stream-Anfragen des Players: wie eine MAG-Box mit MAC-Cookie. */
        fun streamHeaders(p: Profile): Map<String, String> {
            val mac = findMac(p.user) ?: p.user.trim().uppercase()
            return mapOf(
                "Cookie" to "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=en; timezone=${URLEncoder.encode(TimeZone.getDefault().id, "UTF-8")}",
                "X-User-Agent" to "Model: MAG250; Link: WiFi",
            )
        }
    }
}
