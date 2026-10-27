package com.tv26.player.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * TV-Programm. Quellen: eine oder mehrere XMLTV-Dateien, das MAC-Portal
 * und das kurze Programm pro Kanal (wird automatisch nachgeladen).
 */
object Epg {
    /** XMLTV: Kanal-ID (klein) -> Sendungen */
    @Volatile private var data: Map<String, List<Program>> = emptyMap()
    /** normalisierter Name oder ID -> Kanal-ID */
    @Volatile private var names: Map<String, String> = emptyMap()
    /** Kanal-ID (klein) -> Sender-Logo aus der XMLTV-Datei (<icon src>) */
    @Volatile private var icons: Map<String, String> = emptyMap()
    /** Item-ID -> Sendungen (vom Portal oder einzeln nachgeladen) */
    private val extra = ConcurrentHashMap<String, List<Program>>()
    private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Wie lange eine heruntergeladene EPG-Datei gültig ist */
    const val MAX_AGE = 6 * 3_600_000L

    val isEmpty get() = data.isEmpty() && extra.isEmpty()

    fun clear() {
        data = emptyMap()
        names = emptyMap()
        icons = emptyMap()
        extra.clear()
        requested.clear()
    }

    private val prefixRe = Regex("""^\s*[\p{L}]{2,3}\s*[:|]\s*""")
    private val tagRe = Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|h264|raw|backup|orig|multi)\b""")
    private val nonAlnum = Regex("""[^\p{L}\p{N}]""")

    /** "TR: TRT 1 HD" -> "trt1", damit Namen aus Playlist und EPG zusammenpassen. */
    fun norm(s: String): String =
        s.lowercase(Locale.ROOT).replace(prefixRe, "").replace(tagRe, "").replace(nonAlnum, "")

    fun programs(item: Item): List<Program> {
        extra[item.id]?.let { if (it.isNotEmpty()) return it }
        val d = data
        if (d.isEmpty()) return emptyList()
        val n = names
        val id = item.epgId.lowercase(Locale.ROOT)
        return (if (id.isNotEmpty()) d[id] ?: n[norm(id)]?.let { d[it] } else null)
            ?: n[norm(item.name)]?.let { d[it] }
            ?: emptyList()
    }

    /** Sender-Logo aus dem TV-Programm, gleiche Zuordnung wie beim Programm. */
    fun icon(item: Item): String? {
        val ic = icons
        if (ic.isEmpty()) return null
        val n = names
        val id = item.epgId.lowercase(Locale.ROOT)
        return (if (id.isNotEmpty()) ic[id] ?: n[norm(id)]?.let { ic[it] } else null) ?: n[norm(item.name)]?.let { ic[it] }
    }

    fun nowNext(item: Item, now: Long = System.currentTimeMillis()): Pair<Program?, Program?> {
        val list = programs(item)
        val i = list.indexOfFirst { it.start <= now && it.stop > now }
        if (i < 0) return null to list.firstOrNull { it.start > now }
        return list[i] to list.getOrNull(i + 1)
    }

    fun put(itemId: String, list: List<Program>) {
        if (list.isNotEmpty()) extra[itemId] = list
    }

    /** true, wenn für diesen Kanal noch kein Programm da ist und noch nicht danach gefragt wurde. */
    fun shouldFetch(item: Item) = programs(item).isEmpty() && requested.add(item.id)

    /**
     * Lädt alle XMLTV-Quellen (mit Datei-Cache) und führt sie zusammen.
     * Gibt die Zahl der Kanäle mit Programm zurück.
     */
    fun load(urls: List<String>, cacheDir: File, force: Boolean): Int {
        cacheDir.mkdirs()
        val map = HashMap<String, ArrayList<Program>>()
        val nm = HashMap<String, String>()
        val ic = HashMap<String, String>()
        var last: Exception? = null
        for (url in urls.distinct()) {
            try {
                val file = File(cacheDir, "epg_${url.hashCode().toUInt()}.xml")
                val fresh = file.exists() && file.length() > 0 && System.currentTimeMillis() - file.lastModified() < MAX_AGE
                if (force || !fresh) {
                    val tmp = File(cacheDir, file.name + ".tmp")
                    Net.open(url).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.length() == 0L) { tmp.delete(); continue }
                    tmp.renameTo(file.also { it.delete() })
                }
                file.inputStream().buffered().use { parse(it, map, nm, ic) }
            } catch (e: Exception) {
                last = e
            }
        }
        if (map.isEmpty() && last != null) throw last
        map.values.forEach { list -> list.sortBy { it.start } }
        data = map
        names = nm
        icons = ic
        return map.size
    }

    private fun parse(input: InputStream, map: HashMap<String, ArrayList<Program>>, nm: HashMap<String, String>, ic: HashMap<String, String>) {
        val f1 = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val f2 = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fun time(s: String?): Long {
            val t = s?.trim() ?: return 0
            return try {
                if (t.length > 14 && ' ' in t) f1.parse(t)!!.time else f2.parse(t.take(14))!!.time
            } catch (e: Exception) { 0 }
        }

        val now = System.currentTimeMillis()
        val from = now - 8 * DAY
        val to = now + 8 * DAY
        try {
            val xp = Xml.newPullParser()
            xp.setInput(input, null)
            var ch = ""
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var inProg = false
            var chanId: String? = null
            var ev = xp.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (xp.name) {
                        "channel" -> chanId = xp.getAttributeValue(null, "id")?.lowercase(Locale.ROOT)?.also {
                            nm.putIfAbsent(norm(it), it)
                        }
                        "display-name" -> {
                            val n = norm(xp.nextText())
                            chanId?.let { if (n.isNotEmpty()) nm.putIfAbsent(n, it) }
                        }
                        "programme" -> {
                            inProg = true
                            ch = xp.getAttributeValue(null, "channel")?.lowercase(Locale.ROOT) ?: ""
                            start = time(xp.getAttributeValue(null, "start"))
                            stop = time(xp.getAttributeValue(null, "stop"))
                            title = ""
                            desc = ""
                        }
                        "icon" -> if (!inProg) chanId?.let { id ->
                            xp.getAttributeValue(null, "src")?.takeIf { it.startsWith("http") }?.let { ic.putIfAbsent(id, it) }
                        }
                        "title" -> if (inProg && title.isEmpty()) title = xp.nextText()
                        "desc" -> if (inProg && desc.isEmpty()) desc = xp.nextText()
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    when (xp.name) {
                        "channel" -> chanId = null
                        "programme" -> {
                            inProg = false
                            if (ch.isNotEmpty() && stop > from && start < to && stop > start) {
                                map.getOrPut(ch) { ArrayList() }.add(Program(start, stop, title.trim(), desc.trim()))
                            }
                        }
                    }
                }
                ev = xp.next()
            }
        } catch (e: Exception) {
            // Kaputte Datei: was wir schon gelesen haben, behalten wir
            if (map.isEmpty()) throw e
        }
    }
}
