package com.tv26.player.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Fehlende Logos und Cover automatisch finden. Reihenfolge:
 * 1. eigene Quelle (Playlist / Xtream-Details / Portal)
 * 2. Sender-Logos aus dem TV-Programm (XMLTV <icon>)
 * 3. freie Logo-Datenbank iptv-org (nur Live-Sender)
 * 4. TMDB (Filme/Serien, nur mit eigenem kostenlosem API-Schlüssel)
 * Gefundene Bilder werden gespeichert und nicht jedes Mal neu gesucht.
 */
object Art {
    /** Erhöht sich, wenn neue Bilder gefunden wurden (damit die Listen neu zeichnen) */
    var version by mutableIntStateOf(0)

    private lateinit var cacheFile: File
    private lateinit var iptvFile: File
    /** Item-ID -> Bild-URL ("" = nichts gefunden) */
    private val cache = ConcurrentHashMap<String, String>()
    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var iptvOrg: Map<String, String> = emptyMap()
    private val sem = Semaphore(3)
    private var saveJob: Job? = null

    private const val IPTV_ORG_URL = "https://iptv-org.github.io/api/logos.json"
    private const val WEEK = 7 * DAY

    fun init(ctx: Context) {
        cacheFile = File(ctx.filesDir, "art_cache.json")
        iptvFile = File(ctx.cacheDir, "iptv_org_logos.json")
        runCatching {
            val o = JSONObject(cacheFile.readText())
            o.keys().forEach { cache[it] = o.getString(it) }
        }
    }

    /** Das beste bekannte Bild für einen Eintrag (sofort, ohne Netzwerk). */
    fun url(item: Item): String {
        if (item.logo.isNotBlank()) return item.logo
        cache[item.id]?.let { if (it.isNotEmpty()) return it }
        if (item.kind == Kind.LIVE) {
            Epg.icon(item)?.let { return it }
            iptvOrg[Epg.norm(item.name)]?.let { return it }
        }
        return ""
    }

    /** Fehlt bei einem Film/einer Serie das Bild, im Hintergrund danach suchen (einmal pro Eintrag). */
    fun request(item: Item) {
        if (item.kind == Kind.LIVE || item.logo.isNotBlank() || cache.containsKey(item.id) || !requested.add(item.id)) return
        Repo.scope.launch {
            val found = sem.withPermit { withContext(Dispatchers.IO) { runCatching { find(item) }.getOrNull() } }
            cache[item.id] = found.orEmpty()
            if (!found.isNullOrEmpty()) version++
            scheduleSave()
        }
    }

    private fun find(item: Item): String? {
        val p = Repo.profile
        // 1. Eigene Quelle: Xtream-Details
        if (p?.type == SourceType.XTREAM && item.streamId.isNotEmpty()) {
            val x = Xtream(p, Repo.liveExt)
            val u = if (item.kind == Kind.MOVIE) x.vodInfo(item.streamId).let { it["movie_image"] ?: it["cover_big"] }
            else x.seriesCover(item.streamId)
            if (!u.isNullOrBlank() && u.startsWith("http")) return u
        }
        // 4. TMDB (nur mit Schlüssel)
        return tmdb(item)
    }

    /** Film-/Serien-Cover von TMDB. Braucht einen kostenlosen persönlichen API-Schlüssel. */
    private fun tmdb(item: Item): String? {
        val key = Repo.store.str("tmdb_key").trim()
        if (key.isEmpty()) return null
        // Name aufräumen: "DE - Titel (2019) [4K]" -> "Titel", Jahr 2019
        var name = item.name.replace(Regex("""^\s*[\p{L}]{2,3}\s*[:|\-]\s*"""), "")
        val year = Regex("""\((19|20)\d{2}\)""").find(name)?.value?.trim('(', ')') ?: item.year.take(4).takeIf { it.length == 4 }
        name = name.replace(Regex("""[\[(].*?[\])]"""), "").replace(Regex("""\b(4K|UHD|FHD|HD|SD|MULTI)\b""", RegexOption.IGNORE_CASE), "").trim()
        if (name.isEmpty()) return null
        val type = if (item.kind == Kind.SERIES) "tv" else "movie"
        val lang = if (L.lang == "tr") "tr-TR" else "de-DE"
        var url = "https://api.themoviedb.org/3/search/$type?query=${URLEncoder.encode(name, "UTF-8")}&language=$lang"
        if (year != null) url += if (type == "tv") "&first_air_date_year=$year" else "&year=$year"
        // Kurzer v3-Schlüssel als Parameter, langer v4-Token als "Bearer"
        val headers = if (key.length > 40) mapOf("Authorization" to "Bearer $key") else emptyMap()
        if (key.length <= 40) url += "&api_key=${URLEncoder.encode(key, "UTF-8")}"
        val res = JSONObject(Net.text(url, headers, readTimeout = 15_000)).optJSONArray("results") ?: return null
        for (i in 0 until res.length()) {
            val path = res.getJSONObject(i).s("poster_path")
            if (path.isNotEmpty()) return "https://image.tmdb.org/t/p/w342$path"
        }
        return null
    }

    /**
     * Freie Logo-Datenbank iptv-org laden (wird eine Woche gespeichert).
     * Nur nötig, wenn Live-Sender ohne Logo da sind.
     */
    suspend fun loadIptvOrg(live: List<Item>) {
        if (live.none { it.logo.isBlank() }) return
        withContext(Dispatchers.IO) {
            runCatching {
                val fresh = iptvFile.exists() && System.currentTimeMillis() - iptvFile.lastModified() < WEEK
                if (!fresh) Net.open(IPTV_ORG_URL).use { input -> iptvFile.outputStream().use { input.copyTo(it) } }
                val a = JSONArray(iptvFile.readText())
                val map = HashMap<String, String>()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val url = o.s("url")
                    // "TRT1.tr" -> "trt1"
                    val key = Epg.norm(o.s("channel").substringBefore('.'))
                    if (key.isNotEmpty() && url.startsWith("http")) map.putIfAbsent(key, url)
                }
                iptvOrg = map
            }
        }
        version++
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = Repo.scope.launch {
            delay(3000)
            withContext(Dispatchers.IO) {
                runCatching {
                    val o = JSONObject()
                    cache.entries.toList().takeLast(5000).forEach { (k, v) -> o.put(k, v) }
                    cacheFile.writeText(o.toString())
                }
            }
        }
    }
}
