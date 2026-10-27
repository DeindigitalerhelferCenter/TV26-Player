package com.tv26.player.data

import java.io.InputStream

object M3uParser {
    private val attrRe = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val vodExt = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm")

    class Result(val items: List<Item>, val epgUrl: String)

    /** "Serienname S01 E02 Titel" -> Name, Staffel, Folge, Titel */
    val EPISODE_RE = Regex("""(?i)^(.*?)[\s._\-|:]*S(\d{1,2})\s*[._\-]?\s*E(\d{1,4})\b\s*[-:.]?\s*(.*)$""")

    /**
     * Fasst einzelne Folgen (Kind.SERIES) zu Serien zusammen.
     * Gibt die Serien und die Folgen pro Serie (Schlüssel = Item.streamId) zurück.
     */
    fun groupSeries(eps: List<Item>): Pair<List<Item>, Map<String, List<Episode>>> {
        val series = LinkedHashMap<String, Item>()
        val map = HashMap<String, ArrayList<Episode>>()
        for (e in eps) {
            val m = EPISODE_RE.find(e.name)
            val name = m?.groupValues?.get(1)?.trim()?.trimEnd('-', ':', '|', '.', ' ')?.ifBlank { null }
                ?: e.name.replace(Regex("""\s*\d+$"""), "").trim().ifBlank { e.name }
            val key = "${e.group}|$name"
            series.getOrPut(key) {
                Item(id = "m3u:s:$key", name = name, url = "", logo = e.logo, group = e.group, kind = Kind.SERIES, streamId = key)
            }
            val list = map.getOrPut(key) { ArrayList() }
            val num = m?.groupValues?.get(3)?.toIntOrNull() ?: (list.size + 1)
            val title = m?.groupValues?.get(4)?.trim()?.ifBlank { null } ?: tx("Folge %s", num)
            list += Episode(e.url, title, m?.groupValues?.get(2)?.toIntOrNull() ?: 1, num, e.url, "", e.logo, "")
        }
        return series.values.toList() to map.mapValues { (_, v) -> v.sortedWith(compareBy({ it.season }, { it.num })) }
    }

    fun parse(input: InputStream): Result {
        val items = ArrayList<Item>()
        var epg = ""
        var info: String? = null
        var grp: String? = null
        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                // Manche Dateien beginnen mit einem unsichtbaren BOM-Zeichen
                val line = raw.trim().removePrefix("﻿").trim()
                when {
                    line.startsWith("#EXTM3U") -> {
                        val a = attrs(line)
                        epg = a["url-tvg"] ?: a["x-tvg-url"] ?: ""
                    }
                    line.startsWith("#EXTINF") -> { info = line; grp = null }
                    line.startsWith("#EXTGRP:") -> grp = line.substringAfter(':').trim()
                    line.isEmpty() || line.startsWith("#") -> Unit
                    else -> {
                        items += item(info ?: "#EXTINF:-1,$line", line, grp)
                        info = null
                        grp = null
                    }
                }
            }
        }
        // Mehrere EPG-Links sind mit Komma getrennt: wir nehmen den ersten
        return Result(items, epg.substringBefore(',').trim())
    }

    private fun attrs(s: String) = attrRe.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun item(inf: String, url: String, grp: String?): Item {
        val a = attrs(inf)
        val q = inf.lastIndexOf('"')
        val comma = inf.indexOf(',', if (q >= 0) q else 0)
        val name = (if (comma >= 0) inf.substring(comma + 1).trim() else "").ifEmpty { a["tvg-name"] ?: tx("Kanal") }
        val lower = url.lowercase().substringBefore('?')
        // Film = Datei (.mp4/.mkv …) oder /movie/. Folge = /series/ oder Datei mit "S01E02" im Namen.
        // Live-Gruppen wie "Sinema TV" bleiben Live, weil wir nur auf den Link schauen.
        val file = "/movie/" in lower || vodExt.any { lower.endsWith(it) }
        val episode = "/series/" in lower || (file && EPISODE_RE.containsMatchIn(name))
        val kind = when {
            episode -> Kind.SERIES
            file -> Kind.MOVIE
            else -> Kind.LIVE
        }
        return Item(
            id = url,
            name = name,
            url = url,
            logo = a["tvg-logo"] ?: "",
            group = a["group-title"]?.ifBlank { null } ?: grp ?: tx("Andere"),
            kind = kind,
            epgId = a["tvg-id"] ?: "",
            catchupDays = (a["catchup-days"] ?: a["tvg-rec"])?.toIntOrNull() ?: 0,
            catchupType = a["catchup"] ?: "",
            catchupSource = a["catchup-source"] ?: "",
        )
    }
}
