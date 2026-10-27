package com.tv26.player.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun JSONObject.s(k: String): String = if (has(k) && !isNull(k)) optString(k) else ""

/** Client für die Xtream Codes API (player_api.php). */
class Xtream(private val p: Profile, private val liveExt: String) {
    val base = cleanUrl(p.url).trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
    private val u = p.user.trim()
    private val pw = p.pass.trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun auth() = "username=${enc(u)}&password=${enc(pw)}"
    private fun api(action: String, extra: String = "") = "$base/player_api.php?${auth()}&action=$action$extra"

    private fun arr(url: String): JSONArray {
        val t = Net.text(url).trim()
        return if (t.startsWith("[")) JSONArray(t) else JSONArray()
    }

    private fun cats(action: String): Map<String, String> {
        val a = arr(api(action))
        return (0 until a.length()).associate { a.getJSONObject(it).let { o -> o.s("category_id") to o.s("category_name") } }
    }

    fun login() {
        val t = Net.text("$base/player_api.php?${auth()}").trim()
        val ui = runCatching { JSONObject(t).optJSONObject("user_info") }.getOrNull()
        if (ui == null || ui.optInt("auth", 1) == 0) throw IOException(tx("Login fehlgeschlagen. Bitte Server, Benutzer und Passwort prüfen."))
        if (ui.s("status").let { it.isNotEmpty() && !it.equals("Active", true) }) throw IOException(tx("Konto-Status: %s", ui.s("status")))
    }

    fun live(): List<Item> {
        val c = cats("get_live_categories")
        val a = arr(api("get_live_streams"))
        return List(a.length()) {
            val o = a.getJSONObject(it)
            val id = o.s("stream_id")
            val archive = o.optInt("tv_archive") == 1
            Item(
                id = "x:l:$id", name = o.s("name"), url = "$base/live/$u/$pw/$id.$liveExt",
                logo = o.s("stream_icon"), group = c[o.s("category_id")] ?: tx("Andere"), kind = Kind.LIVE,
                epgId = o.s("epg_channel_id"),
                catchupDays = if (archive) o.optInt("tv_archive_duration") else 0,
                catchupType = if (archive) "xtream" else "", streamId = id,
            )
        }
    }

    fun movies(): List<Item> {
        val c = cats("get_vod_categories")
        val a = arr(api("get_vod_streams"))
        return List(a.length()) {
            val o = a.getJSONObject(it)
            val id = o.s("stream_id")
            val ext = o.s("container_extension").ifEmpty { "mp4" }
            Item(
                id = "x:m:$id", name = o.s("name"), url = "$base/movie/$u/$pw/$id.$ext",
                logo = o.s("stream_icon"), group = c[o.s("category_id")] ?: tx("Andere"), kind = Kind.MOVIE,
                rating = o.s("rating"), year = o.s("year").ifEmpty { o.s("releasedate").take(4) }, streamId = id,
            )
        }
    }

    fun series(): List<Item> {
        val c = cats("get_series_categories")
        val a = arr(api("get_series"))
        return List(a.length()) {
            val o = a.getJSONObject(it)
            val id = o.s("series_id")
            Item(
                id = "x:s:$id", name = o.s("name"), url = "", logo = o.s("cover"),
                group = c[o.s("category_id")] ?: tx("Andere"), kind = Kind.SERIES,
                plot = o.s("plot"), rating = o.s("rating"), year = o.s("releaseDate").take(4), streamId = id,
            )
        }
    }

    /** Details zu einem Film: Handlung, Genre, Darsteller, Regie, Dauer. */
    fun vodInfo(streamId: String): Map<String, String> {
        val o = JSONObject(Net.text(api("get_vod_info", "&vod_id=$streamId")))
        val info = o.optJSONObject("info") ?: return emptyMap()
        return listOf("plot", "genre", "cast", "director", "duration", "releasedate", "rating", "movie_image", "cover_big")
            .associateWith { info.s(it) }.filterValues { it.isNotBlank() }
    }

    fun episodes(seriesId: String): List<Episode> {
        val o = JSONObject(Net.text(api("get_series_info", "&series_id=$seriesId")))
        val out = ArrayList<Episode>()
        fun add(e: JSONObject, seasonKey: Int) {
            val info = e.optJSONObject("info")
            val id = e.s("id")
            val ext = e.s("container_extension").ifEmpty { "mp4" }
            out += Episode(
                id = id, title = e.s("title"),
                season = e.optInt("season", seasonKey).takeIf { it > 0 } ?: seasonKey,
                num = e.optInt("episode_num"), url = "$base/series/$u/$pw/$id.$ext",
                plot = info?.s("plot") ?: "", image = info?.s("movie_image") ?: "",
                duration = info?.s("duration") ?: "",
            )
        }
        when (val eps = o.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k ->
                val list = eps.optJSONArray(k) ?: return@forEach
                for (i in 0 until list.length()) add(list.getJSONObject(i), k.toIntOrNull() ?: 1)
            }
            is JSONArray -> for (s in 0 until eps.length()) {
                val list = eps.optJSONArray(s) ?: continue
                for (i in 0 until list.length()) add(list.getJSONObject(i), s + 1)
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.num }))
    }

    fun epgUrl() = "$base/xmltv.php?${auth()}"

    /** Cover einer Serie aus den Serien-Details. */
    fun seriesCover(seriesId: String): String? {
        val info = JSONObject(Net.text(api("get_series_info", "&series_id=$seriesId"))).optJSONObject("info") ?: return null
        return info.s("cover").ifBlank { info.s("cover_big") }.ifBlank { null }
    }

    /** Kurzes TV-Programm für einen Kanal (wenn er in der XMLTV-Datei fehlt). Titel sind Base64. */
    fun shortEpg(streamId: String): List<Program> {
        val a = JSONObject(Net.text(api("get_short_epg", "&stream_id=$streamId&limit=20"))).optJSONArray("epg_listings") ?: return emptyList()
        fun b64(s: String) = runCatching { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)) }.getOrDefault(s)
        return List(a.length()) {
            val o = a.getJSONObject(it)
            Program(o.optLong("start_timestamp") * 1000, o.optLong("stop_timestamp") * 1000, b64(o.s("title")), b64(o.s("description")))
        }.filter { it.stop > it.start }.sortedBy { it.start }
    }

    fun catchupUrl(streamId: String, start: Long, durationMin: Int): String {
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
        return "$base/timeshift/$u/$pw/$durationMin/${f.format(Date(start))}/$streamId.ts"
    }
}
