package com.tv26.player.data

import org.json.JSONObject

enum class SourceType(val label: String) {
    M3U_URL("M3U-Link"), M3U_FILE("M3U-Datei"), XTREAM("Xtream Codes"), STALKER("MAC-Portal"),
}

data class Profile(
    val id: String,
    val name: String,
    val type: SourceType,
    val url: String,
    val user: String = "",
    val pass: String = "",
    val epgUrl: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type.name)
        .put("url", url).put("user", user).put("pass", pass).put("epg", epgUrl)

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            o.getString("id"), o.optString("name"),
            runCatching { SourceType.valueOf(o.optString("type")) }.getOrDefault(SourceType.M3U_URL),
            o.optString("url"), o.optString("user"), o.optString("pass"), o.optString("epg"),
        )
    }
}

enum class Kind(val label: String) { LIVE("Live"), MOVIE("Film"), SERIES("Serie") }

data class Item(
    val id: String,
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
    val kind: Kind,
    val epgId: String = "",
    val catchupDays: Int = 0,
    val catchupType: String = "",
    val catchupSource: String = "",
    val streamId: String = "",
    val plot: String = "",
    val rating: String = "",
    val year: String = "",
)

fun Item.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("url", url).put("logo", logo).put("group", group)
    .put("kind", kind.name).put("epg", epgId).put("sid", streamId)
    .put("plot", plot).put("rating", rating).put("year", year)

fun itemFromJson(o: JSONObject) = Item(
    id = o.getString("id"), name = o.optString("name"), url = o.optString("url"), logo = o.optString("logo"),
    group = o.optString("group"), kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.LIVE),
    epgId = o.optString("epg"), streamId = o.optString("sid"),
    plot = o.optString("plot"), rating = o.optString("rating"), year = o.optString("year"),
)

data class Episode(
    val id: String, val title: String, val season: Int, val num: Int,
    val url: String, val plot: String, val image: String, val duration: String,
)

data class Program(val start: Long, val stop: Long, val title: String, val desc: String)

data class Content(
    val live: List<Item> = emptyList(),
    val movies: List<Item> = emptyList(),
    val series: List<Item> = emptyList(),
) {
    val all: List<Item> get() = live + movies + series
}

/** Ein Eintrag in der Wiedergabe-Liste des Players. */
data class PlayEntry(val title: String, val url: String, val live: Boolean, val item: Item? = null, val image: String = "")

const val DAY = 86_400_000L

private val urlRe = Regex("""(?i)\b(?:https?|rtmp)://[^\s"'<>]+""")
private val macRe = Regex("""([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}""")

/**
 * Holt die echte Adresse aus einer Eingabe. Beim Einfügen kommen oft Zeilenumbrüche
 * oder Zusatztext mit ("…/c/\nMAC Addr: …"), das macht HTTP-Header kaputt.
 */
fun cleanUrl(s: String): String =
    urlRe.find(s)?.value ?: s.trim().lineSequence().firstOrNull()?.trim().orEmpty()

/** Findet eine MAC-Adresse in einem Text ("MAC: 00-1a-79-…" -> "00:1A:79:…"). */
fun findMac(s: String): String? = macRe.find(s)?.value?.replace('-', ':')?.uppercase()
