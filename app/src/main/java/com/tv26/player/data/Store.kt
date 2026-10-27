package com.tv26.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lokaler Speicher. Alles ist als Text gespeichert, damit das Backup einfach ist. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tv26", Context.MODE_PRIVATE)

    fun str(k: String, d: String = ""): String = sp.getString(k, null) ?: d
    fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    fun list(k: String): List<String> = try {
        val a = JSONArray(str(k, "[]"))
        List(a.length()) { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    fun set(k: String): Set<String> = list(k).toSet()
    fun putList(k: String, v: Collection<String>) = put(k, JSONArray(v).toString())
    fun bool(k: String, d: Boolean) = str(k, d.toString()) == "true"
    fun putBool(k: String, v: Boolean) = put(k, v.toString())
    fun float(k: String, d: Float) = str(k).toFloatOrNull() ?: d

    var profiles: List<Profile>
        get() = try {
            val a = JSONArray(str("profiles", "[]"))
            List(a.length()) { Profile.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
        set(v) = put("profiles", JSONArray(v.map { it.toJson() }).toString())

    /** Feste MAC-ID dieses Geräts für MAC-Portale (MAG-Präfix 00:1A:79). Wird einmal erzeugt. */
    fun deviceMac(): String {
        str("device_mac").takeIf { it.isNotBlank() }?.let { return it }
        val r = java.security.SecureRandom()
        val mac = "00:1A:79:" + List(3) { "%02X".format(r.nextInt(256)) }.joinToString(":")
        put("device_mac", mac)
        return mac
    }

    // --- Weiterschauen und "Gesehen" ---
    private var resumeCache: JSONObject? = null
    private var watchedCache: MutableSet<String>? = null

    private fun resume(): JSONObject = resumeCache
        ?: runCatching { JSONObject(str("resume", "{}")) }.getOrDefault(JSONObject()).also { resumeCache = it }

    private fun watched(): MutableSet<String> = watchedCache ?: set("watched").toMutableSet().also { watchedCache = it }

    fun resumePos(url: String): Long = resume().optJSONArray(url)?.optLong(0) ?: 0L

    fun resumeFraction(url: String): Float {
        val a = resume().optJSONArray(url) ?: return 0f
        val d = a.optLong(1)
        return if (d > 0) a.optLong(0).toFloat() / d else 0f
    }

    fun isWatched(url: String) = url in watched()

    fun saveResume(url: String, pos: Long, dur: Long) {
        val r = resume()
        when {
            dur > 0 && pos > dur * 0.95 -> { r.remove(url); markWatched(url) }
            pos > 30_000 -> r.put(url, JSONArray().put(pos).put(dur))
            else -> return
        }
        while (r.length() > 500) r.remove(r.keys().next())
        put("resume", r.toString())
        Repo.watchVersion++
    }

    fun markWatched(url: String) {
        val w = watched()
        if (w.add(url)) putList("watched", w.toList().takeLast(3000))
        Repo.watchVersion++
    }

    /** Fortsetzen-Position und "Gesehen" für einen Link löschen. */
    fun clearProgress(url: String) {
        resume().remove(url)
        put("resume", resume().toString())
        val w = watched()
        if (w.remove(url)) putList("watched", w)
        Repo.watchVersion++
    }

    /** Alle Fortsetzen-Positionen und "Gesehen"-Markierungen löschen. */
    fun clearAllProgress() {
        resumeCache = JSONObject()
        watchedCache = mutableSetOf()
        put("resume", "{}")
        put("watched", "[]")
        Repo.watchVersion++
    }

    // --- Backup ---
    fun exportJson(): String {
        val o = JSONObject()
        sp.all.forEach { (k, v) -> if (v is String) o.put(k, v) }
        // Datum und Uhrzeit des Backups (wird beim Laden angezeigt, aber nicht als Einstellung gespeichert)
        o.put("_backup_created", java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.GERMANY).format(java.util.Date()))
        return o.toString(2)
    }

    /** Lädt ein Backup. Gibt Datum/Uhrzeit des Backups zurück (leer bei alten Backups). */
    fun importJson(t: String): String {
        val o = JSONObject(t)
        if (!o.has("profiles")) throw IllegalArgumentException(tx("Keine TV26-Backup-Datei"))
        val e = sp.edit().clear()
        o.keys().forEach { if (!it.startsWith("_")) e.putString(it, o.optString(it)) }
        e.apply()
        resumeCache = null
        watchedCache = null
        return o.optString("_backup_created")
    }

    companion object {
        /** Zeitstempel für Backup-Dateinamen, z. B. 2026-10-27_14-35 */
        fun stamp(): String = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date())
    }
}
