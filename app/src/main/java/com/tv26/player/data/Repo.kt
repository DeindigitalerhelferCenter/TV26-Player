package com.tv26.player.data

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Zentraler App-Zustand: Playlists, Inhalte, Favoriten, Einstellungen. */
object Repo {
    lateinit var app: Context
    lateinit var store: Store
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var profiles by mutableStateOf(listOf<Profile>())
    var profile by mutableStateOf<Profile?>(null)
    var content by mutableStateOf(Content())
    var loading by mutableStateOf(false)
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    var epgStatus by mutableStateOf("")
    var epgVersion by mutableIntStateOf(0)
    var watchVersion by mutableIntStateOf(0)
    var favs by mutableStateOf(setOf<String>())
    var hidden by mutableStateOf(setOf<String>())
    var locked by mutableStateOf(setOf<String>())
    /** Einzelne Einträge (Item-IDs), ausgeblendet bzw. mit PIN gesperrt */
    var hiddenItems by mutableStateOf(setOf<String>())
    var lockedItems by mutableStateOf(setOf<String>())
    /** Zuletzt im Player gezeigter Kanal (für den Fokus beim Zurückkommen auf dem Fernseher) */
    var lastPlayedId = ""
    /** Eintrag, für den gerade das Menü (lange drücken) offen ist */
    var menuItem by mutableStateOf<Item?>(null)
    var recent by mutableStateOf(listOf<String>())
    var unlocked by mutableStateOf(false)
    var accent by mutableIntStateOf(0)
    /** Ansicht für Live-TV, Filme, Serien: 0 = groß, 1 = mittel, 2 = Liste */
    var viewMode by mutableIntStateOf(1)

    fun cycleViewMode() {
        viewMode = (viewMode + 1) % 3
        store.put("view_mode", "$viewMode")
    }

    val liveExt get() = store.str("live_ext", "ts")

    private val pid get() = profile?.id ?: ""
    /** true, sobald das große EPG geladen ist. Erst dann wird pro Kanal nachgeladen. */
    var epgReady by mutableStateOf(false)
    private var m3uEpg = ""
    private var guessedEpg = ""
    private var loadJob: Job? = null
    private var epgJob: Job? = null
    private var stalkerClient: Stalker? = null
    private val epgSem = Semaphore(2)

    /** Anzahl laufender Ladevorgänge für Filme/Serien (MAC-Portal) */
    var vodBusy by mutableIntStateOf(0)
    /** MAC-Portal: Kategorie-Namen für Filme und Serien (sofort da, Inhalte kommen bei Bedarf) */
    var vodGroups by mutableStateOf<Map<Kind, List<String>>>(emptyMap())
    private var vodCats: List<VodCat> = emptyList()
    private val vodIds = HashSet<String>()
    /** M3U: Folgen pro Serie (Schlüssel = Item.streamId) */
    private var m3uEpisodes: Map<String, List<Episode>> = emptyMap()
    /** Gemerkte Einträge aus Favoriten/Verlauf, auch wenn ihre Kategorie gerade nicht geladen ist */
    private val itemCache = LinkedHashMap<String, Item>()

    /** Eine Film- oder Serien-Kategorie im MAC-Portal, seitenweise geladen. */
    private class VodCat(val id: String, val title: String, val type: String) {
        var next = 1
        var pages = Int.MAX_VALUE
        var busy = false
        val done get() = next > pages
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        store = Store(app)
        Device.init(app)
        L.init(store)
        Art.init(app)
        applySettings()
    }

    fun applySettings() {
        profiles = store.profiles
        accent = store.str("accent", "0").toIntOrNull() ?: 0
        viewMode = store.str("view_mode", "1").toIntOrNull()?.coerceIn(0, 2) ?: 1
        Net.userAgent = store.str("ua").ifBlank { Net.DEFAULT_UA }
        Net.setDns(store.str("dns_mode", "auto"))
    }

    // --- Playlists ---
    fun saveProfile(p: Profile) {
        profiles = if (profiles.any { it.id == p.id }) profiles.map { if (it.id == p.id) p else it } else profiles + p
        store.profiles = profiles
    }

    fun deleteProfile(p: Profile) {
        profiles = profiles.filter { it.id != p.id }
        store.profiles = profiles
    }

    fun open(p: Profile) {
        profile = p
        store.put("last_profile", p.id)
        favs = store.set("fav_${p.id}")
        hidden = store.set("hidden_${p.id}")
        locked = store.set("locked_${p.id}")
        hiddenItems = store.set("hiddenItems_${p.id}")
        lockedItems = store.set("lockedItems_${p.id}")
        recent = store.list("recent_${p.id}")
        unlocked = false
        itemCache.clear()
        runCatching {
            val o = org.json.JSONObject(store.str("items_${p.id}", "{}"))
            o.keys().forEach { k -> itemCache[k] = itemFromJson(o.getJSONObject(k)) }
        }
        reload()
    }

    fun close() {
        loadJob?.cancel()
        epgJob?.cancel()
        resetVod()
        m3uEpisodes = emptyMap()
        stalkerClient = null
        epgReady = false
        profile = null
        content = Content()
        Epg.clear()
        epgVersion++
        store.put("last_profile", "")
    }

    fun reload() {
        val p = profile ?: return
        loadJob?.cancel()
        resetVod()
        loadJob = scope.launch {
            loading = true
            error = null
            status = tx("Lade Playlist…")
            try {
                content = withContext(Dispatchers.IO) { loadContent(p) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
            startEpg(p)
            // Fehlende Sender-Logos aus der freien Logo-Datenbank (im Hintergrund)
            launch { Art.loadIptvOrg(content.live) }
        }
    }

    private fun resetVod() {
        vodCats = emptyList()
        vodGroups = emptyMap()
        vodIds.clear()
        vodBusy = 0
    }

    /** Welche Portal-Kategorien gehören zu diesem Tab? (Ältere Portale haben Serien in den Film-Kategorien.) */
    private fun catsFor(kind: Kind): List<VodCat> {
        val series = vodCats.filter { it.type == "series" }
        return if (kind == Kind.SERIES && series.isNotEmpty()) series else vodCats.filter { it.type == "vod" }
    }

    /**
     * MAC-Portal: Filme/Serien NUR bei Bedarf laden.
     * [group] = geöffnete Kategorie (nächste Seite davon), null = "Alle" (nächste noch nicht fertige Kategorie).
     * Wird beim Öffnen einer Kategorie und beim Scrollen ans Ende aufgerufen.
     */
    fun vodNeedMore(kind: Kind, group: String?) {
        val s = stalkerClient ?: return
        val cats = catsFor(kind).filter { !it.done && !it.busy }
        val targets = if (group != null) cats.filter { it.title == group } else cats.take(1)
        targets.forEach { cat ->
            cat.busy = true
            vodBusy++
            scope.launch {
                val res = runCatching { withContext(Dispatchers.IO) { s.vodPage(cat.type, cat.id, cat.title, cat.next) } }.getOrNull()
                if (res != null) {
                    cat.pages = res.first
                    cat.next++
                    val fresh = res.second.filter { vodIds.add(it.id) }
                    if (fresh.isNotEmpty()) {
                        content = content.copy(
                            movies = content.movies + fresh.filter { it.kind == Kind.MOVIE },
                            series = content.series + fresh.filter { it.kind == Kind.SERIES },
                        )
                    }
                } else {
                    cat.pages = 0 // Fehler: diese Kategorie nicht endlos neu versuchen
                }
                cat.busy = false
                vodBusy = (vodBusy - 1).coerceAtLeast(0)
            }
        }
    }

    /** Laufenden Portal-Stream am Leben halten (wird vom Player regelmäßig aufgerufen). */
    suspend fun keepAlive() {
        val s = stalkerClient ?: return
        withContext(Dispatchers.IO) { s.keepAlive() }
    }

    /** Folgen einer Serie, egal aus welcher Quelle. */
    suspend fun episodes(series: Item): List<Episode> = withContext(Dispatchers.IO) {
        when (profile?.type) {
            SourceType.XTREAM -> xtream()?.episodes(series.streamId).orEmpty()
            SourceType.STALKER -> stalkerClient?.episodes(series.streamId).orEmpty()
            else -> m3uEpisodes[series.streamId].orEmpty()
        }
    }

    /** EPG laden und danach alle 6 Stunden automatisch erneuern. */
    private fun startEpg(p: Profile) {
        epgJob?.cancel()
        epgJob = scope.launch {
            var force = false
            while (true) {
                loadEpg(p, force)
                force = true
                delay(Epg.MAX_AGE)
            }
        }
    }

    fun reloadEpg() {
        val p = profile ?: return
        epgJob?.cancel()
        epgJob = scope.launch {
            loadEpg(p, force = true)
            delay(Epg.MAX_AGE)
            startEpg(p)
        }
    }

    private fun loadContent(p: Profile): Content = when (p.type) {
        SourceType.XTREAM -> {
            val x = Xtream(p, liveExt)
            status = tx("Anmelden…")
            x.login()
            status = tx("Live-Kanäle laden…")
            val live = x.live()
            status = tx("Filme laden…")
            val mov = runCatching { x.movies() }.getOrDefault(emptyList())
            status = tx("Serien laden…")
            val ser = runCatching { x.series() }.getOrDefault(emptyList())
            Content(live, mov, ser)
        }
        SourceType.STALKER -> {
            val s = Stalker(p)
            status = tx("Anmelden…")
            s.login()
            stalkerClient = s
            status = tx("Live-Kanäle laden…")
            val live = s.live()
            // Nur die Kategorien holen (2 schnelle Anfragen). Inhalte kommen erst, wenn man sie öffnet.
            val cats = listOf("vod", "series").flatMap { type ->
                runCatching { s.vodCategories(type) }.getOrDefault(emptyList()).map { (id, t) -> VodCat(id, t, type) }
            }
            vodCats = cats
            vodGroups = mapOf(Kind.MOVIE to catsFor(Kind.MOVIE).map { it.title }, Kind.SERIES to catsFor(Kind.SERIES).map { it.title })
            Content(live, emptyList(), emptyList())
        }
        else -> {
            val input = if (p.type == SourceType.M3U_FILE) {
                app.contentResolver.openInputStream(Uri.parse(p.url)) ?: throw IOException(tx("Datei kann nicht gelesen werden"))
            } else Net.open(cleanUrl(p.url))
            val r = input.use { M3uParser.parse(it) }
            m3uEpg = r.epgUrl
            if (r.items.isEmpty()) throw IOException(tx("Die Playlist ist leer oder ungültig"))
            guessedEpg = guessXmltv(cleanUrl(p.url), r.items)
            // Folgen automatisch zu Serien zusammenfassen
            val (series, eps) = M3uParser.groupSeries(r.items.filter { it.kind == Kind.SERIES })
            m3uEpisodes = eps
            Content(r.items.filter { it.kind == Kind.LIVE }, r.items.filter { it.kind == Kind.MOVIE }, series)
        }
    }

    /**
     * Viele M3U-Links kommen von Xtream-Servern (get.php?username=…, /live/user/pass/123.ts).
     * Dann gibt es dort auch eine EPG-Datei: …/xmltv.php?username=…&password=…
     */
    private fun guessXmltv(m3uUrl: String, items: List<Item>): String {
        Regex("""^(https?://[^/?#]+)/get\.php\?.*?username=([^&]+)&password=([^&]+)""").find(m3uUrl)?.let {
            val (base, u, pw) = it.destructured
            return "$base/xmltv.php?username=$u&password=$pw"
        }
        val re = Regex("""^(https?://[^/?#]+)/(?:live/)?([^/]+)/([^/]+)/\d+(?:\.\w+)?$""")
        items.firstOrNull { it.kind == Kind.LIVE && re.matches(it.url) }?.let { item ->
            val (base, u, pw) = re.find(item.url)!!.destructured
            return "$base/xmltv.php?username=$u&password=$pw"
        }
        return ""
    }

    /** Alle EPG-Quellen dieser Playlist. Mehrere Adressen dürfen mit Komma getrennt sein. */
    private fun epgUrls(p: Profile): List<String> {
        fun split(s: String) = s.split(',', ' ', '\n').map { it.trim() }.filter { it.startsWith("http") }
        val list = split(p.epgUrl).toMutableList()
        when (p.type) {
            SourceType.XTREAM -> list += Xtream(p, liveExt).epgUrl()
            SourceType.M3U_URL, SourceType.M3U_FILE -> {
                list += split(m3uEpg)
                if (guessedEpg.isNotEmpty()) list += guessedEpg
            }
            SourceType.STALKER -> Unit
        }
        return list.distinct()
    }

    private suspend fun loadEpg(p: Profile, force: Boolean = false) {
        epgReady = false
        epgStatus = tx("TV-Programm wird geladen…")
        val urls = epgUrls(p)
        var err: String? = null
        if (urls.isNotEmpty()) {
            try {
                withContext(Dispatchers.IO) { Epg.load(urls, File(app.cacheDir, "epg"), force) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err = tx("EPG-Fehler: %s", e.message)
            }
        }
        // MAC-Portal: Programm für alle Kanäle direkt vom Portal
        stalkerClient?.let { s ->
            runCatching {
                // Schwache Geräte: nur 24 Stunden TV-Programm (spart Speicher)
                val all = withContext(Dispatchers.IO) { s.epgAll(if (Device.lowEnd) 24 else 48) }
                content.live.forEach { item -> all[item.streamId]?.let { Epg.put(item.id, it) } }
            }
        }
        epgStatus = when {
            !Epg.isEmpty -> ""
            err != null -> err
            urls.isEmpty() && p.type != SourceType.STALKER && p.type != SourceType.XTREAM ->
                tx("Keine EPG-Quelle. Du kannst eine EPG-URL bei der Playlist eintragen.")
            else -> tx("Kein TV-Programm gefunden")
        }
        epgReady = true
        epgVersion++
    }

    /** Kanal ohne Programm: kurzes Programm direkt beim Server holen (Xtream / MAC-Portal). */
    fun fetchShortEpg(item: Item) {
        val p = profile ?: return
        if (item.kind != Kind.LIVE || item.streamId.isEmpty() || !epgReady) return
        if (p.type != SourceType.XTREAM && p.type != SourceType.STALKER) return
        if (!Epg.shouldFetch(item)) return
        scope.launch {
            val list = runCatching {
                epgSem.withPermit {
                    withContext(Dispatchers.IO) {
                        if (p.type == SourceType.XTREAM) Xtream(p, liveExt).shortEpg(item.streamId)
                        else stalkerClient?.shortEpg(item.streamId).orEmpty()
                    }
                }
            }.getOrDefault(emptyList())
            if (list.isNotEmpty() && profile?.id == p.id) {
                Epg.put(item.id, list)
                epgVersion++
            }
        }
    }

    /** Wandelt Portal-Links ("stalker:…") in echte, kurz gültige Stream-Links um. */
    suspend fun resolveUrl(url: String): String {
        if (!url.startsWith("stalker:")) return url
        val type = url.removePrefix("stalker:").substringBefore(':')
        val rest = url.removePrefix("stalker:$type:")
        // Folgen: "cmd|Folgennummer"
        val cmd = if (type == "vod" && '|' in rest) rest.substringBeforeLast('|') else rest
        val series = if (type == "vod" && '|' in rest) rest.substringAfterLast('|') else ""
        return withContext(Dispatchers.IO) {
            val s = stalkerClient ?: profile?.takeIf { it.type == SourceType.STALKER }?.let { Stalker(it).also { c -> c.login(); stalkerClient = c } }
                ?: throw IOException(tx("Link konnte nicht erstellt werden"))
            // Langsame/überlastete Portal-Server: bis zu 3 Versuche, den Stream-Link zu holen
            var last: Exception? = null
            repeat(3) { attempt ->
                try {
                    return@withContext s.createLink(type, cmd, series).also {
                        android.util.Log.i("TV26", "create_link $type -> ${com.tv26.player.player.maskUrl(it)}")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("TV26", "create_link $type Versuch ${attempt + 1} fehlgeschlagen: ${e.message}")
                    last = e
                    delay(1000)
                }
            }
            throw last ?: IOException(tx("Link konnte nicht erstellt werden"))
        }
    }

    fun xtream(): Xtream? = profile?.takeIf { it.type == SourceType.XTREAM }?.let { Xtream(it, liveExt) }

    // --- Favoriten, Verlauf, Gruppen ---
    private fun Set<String>.toggle(k: String) = if (k in this) this - k else this + k

    fun toggleFav(item: Item) {
        favs = favs.toggle(item.id)
        store.putList("fav_$pid", favs)
        cacheItem(item)
    }

    fun addRecent(item: Item) {
        recent = (listOf(item.id) + recent.filter { it != item.id }).take(100)
        store.putList("recent_$pid", recent)
        cacheItem(item)
    }

    /** Eintrag merken, damit Favoriten/Verlauf ihn auch ohne geladene Kategorie zeigen können. */
    private fun cacheItem(item: Item) {
        itemCache.remove(item.id)
        itemCache[item.id] = item
        val keep = favs + recent + hiddenItems + lockedItems
        itemCache.keys.retainAll { it in keep }
        val o = org.json.JSONObject()
        itemCache.values.forEach { o.put(it.id, it.toJson()) }
        store.put("items_$pid", o.toString())
    }

    private var indexOf: Content? = null
    private var index: Map<String, Item> = emptyMap()

    /** Eintrag per ID finden: erst in den geladenen Inhalten, dann im Speicher. */
    fun lookup(id: String): Item? {
        val c = content
        if (indexOf !== c) {
            index = (c.live + c.movies + c.series).associateBy { it.id }
            indexOf = c
        }
        return index[id] ?: itemCache[id]
    }

    /** Einen Eintrag aus dem Verlauf entfernen (bei Filmen auch die Fortsetzen-Position). */
    fun removeRecent(item: Item) {
        recent = recent.filter { it != item.id }
        store.putList("recent_$pid", recent)
        if (item.kind == Kind.MOVIE) store.clearProgress(item.url)
    }

    /** Ganzen Verlauf löschen. Mit [all] auch alle Fortsetzen-Positionen und "Gesehen"-Markierungen. */
    fun clearHistory(all: Boolean) {
        recent = emptyList()
        store.putList("recent_$pid", recent)
        if (all) store.clearAllProgress()
    }

    fun groupKey(k: Kind, g: String) = "${k.name}|$g"

    fun toggleHidden(key: String) {
        hidden = hidden.toggle(key)
        store.putList("hidden_$pid", hidden)
    }

    fun toggleLocked(key: String) {
        locked = locked.toggle(key)
        store.putList("locked_$pid", locked)
    }

    /** Viele Gruppen auf einmal ein-/ausblenden. */
    fun setGroupsHidden(keys: Collection<String>, hide: Boolean) {
        hidden = if (hide) hidden + keys else hidden - keys.toSet()
        store.putList("hidden_$pid", hidden)
    }

    /** Viele Gruppen auf einmal mit PIN sperren / entsperren. */
    fun setGroupsLocked(keys: Collection<String>, lock: Boolean) {
        locked = if (lock) locked + keys else locked - keys.toSet()
        store.putList("locked_$pid", locked)
    }

    // --- Einzelne Kanäle, Filme, Serien ausblenden oder sperren ---
    fun toggleItemHidden(item: Item) {
        hiddenItems = hiddenItems.toggle(item.id)
        store.putList("hiddenItems_$pid", hiddenItems)
        cacheItem(item)
    }

    fun toggleItemLocked(item: Item) {
        lockedItems = lockedItems.toggle(item.id)
        store.putList("lockedItems_$pid", lockedItems)
        cacheItem(item)
    }

    /** "OK" in der Verwaltung: gespeicherten Stand der Gruppen komplett ersetzen. */
    fun saveGroupFlags(hiddenKeys: Set<String>, lockedKeys: Set<String>) {
        hidden = hiddenKeys
        locked = lockedKeys
        store.putList("hidden_$pid", hidden)
        store.putList("locked_$pid", locked)
    }

    /** "OK" in der Verwaltung: gespeicherten Stand der einzelnen Einträge komplett ersetzen. */
    fun saveItemFlags(hiddenIds: Set<String>, lockedIds: Set<String>) {
        hiddenItems = hiddenIds
        lockedItems = lockedIds
        store.putList("hiddenItems_$pid", hiddenItems)
        store.putList("lockedItems_$pid", lockedItems)
    }

    /** Gibt es überhaupt etwas Gesperrtes? (dann zeigt die App das Schloss oben) */
    val hasLocks get() = locked.isNotEmpty() || lockedItems.isNotEmpty()

    fun isHidden(it: Item) = groupKey(it.kind, it.group) in hidden || it.id in hiddenItems
    fun isLocked(k: Kind, g: String) = !unlocked && groupKey(k, g) in locked
    fun isItemLocked(it: Item) = !unlocked && (it.id in lockedItems || groupKey(it.kind, it.group) in locked)
    fun visible(it: Item) = !isHidden(it) && !isItemLocked(it)
}
