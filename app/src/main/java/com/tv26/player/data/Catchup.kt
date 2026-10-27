package com.tv26.player.data

import java.util.Calendar
import java.util.Locale

/** Baut die URL für "nachträglich ansehen" (Catch-up / Archiv). */
object Catchup {
    fun available(item: Item) = item.kind == Kind.LIVE && item.catchupDays > 0

    fun url(item: Item, p: Program): String? {
        if (!available(item)) return null
        if (item.catchupType == "xtream") {
            val x = Repo.xtream() ?: return null
            return x.catchupUrl(item.streamId, p.start, ((p.stop - p.start) / 60_000).toInt())
        }
        val start = p.start / 1000
        val end = p.stop / 1000
        val now = System.currentTimeMillis() / 1000
        val sep = if ('?' in item.url) "&" else "?"
        return when (item.catchupType.lowercase(Locale.US)) {
            "append" -> item.url + fill(item.catchupSource, p, start, end, now)
            "shift", "timeshift" -> "${item.url}${sep}utc=$start&lutc=$now"
            "flussonic", "fs" -> item.url.replace(Regex("""(index|video|mono)[^/]*\.m3u8"""), "timeshift_abs-$start.m3u8")
                .replace(Regex("""mpegts$"""), "timeshift_abs-$start.ts")
            else -> if (item.catchupSource.isNotEmpty()) fill(item.catchupSource, p, start, end, now)
            else "${item.url}${sep}utc=$start&lutc=$now"
        }
    }

    private fun fill(t: String, p: Program, start: Long, end: Long, now: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = p.start }
        fun two(v: Int) = v.toString().padStart(2, '0')
        val map = mapOf(
            "utc" to "$start", "start" to "$start", "utcend" to "$end", "end" to "$end",
            "lutc" to "$now", "now" to "$now", "timestamp" to "$now",
            "duration" to "${end - start}", "offset" to "${now - start}",
            "Y" to "${c.get(Calendar.YEAR)}", "m" to two(c.get(Calendar.MONTH) + 1), "d" to two(c.get(Calendar.DAY_OF_MONTH)),
            "H" to two(c.get(Calendar.HOUR_OF_DAY)), "M" to two(c.get(Calendar.MINUTE)), "S" to two(c.get(Calendar.SECOND)),
        )
        var out = t
        for ((k, v) in map) out = out.replace("\${$k}", v).replace("{$k}", v)
        return out
    }
}
