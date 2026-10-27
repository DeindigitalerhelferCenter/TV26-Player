package com.tv26.player.player

import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tv26.player.App
import com.tv26.player.R
import com.tv26.player.data.Net
import com.tv26.player.data.tx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Eine laufende Aufnahme. */
class Rec(val title: String, val startedAt: Long)

/** Nimmt Streams als .ts-Datei auf (Ordner Download/TV26). Läuft im Hintergrund, bis man selbst stoppt. */
class RecordService : Service() {
    companion object {
        /** url -> laufende Aufnahme (für die Anzeige im Player) */
        val active = mutableStateMapOf<String, Rec>()

        fun start(ctx: Context, url: String, title: String, isLive: Boolean) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, RecordService::class.java).setAction("start")
                    .putExtra("url", url).putExtra("title", title).putExtra("live", isLive),
            )
        }

        fun stop(ctx: Context, url: String) {
            ctx.startService(Intent(ctx, RecordService::class.java).setAction("stop").putExtra("url", url))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Job>()

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url")
        when (intent?.action) {
            "start" -> if (url != null && url !in jobs) {
                val title = intent.getStringExtra("title") ?: tx("Aufnahme")
                val isLive = intent.getBooleanExtra("live", true)
                android.util.Log.i("TV26", "Aufnahme-Start: $title (live=$isLive)")
                active[url] = Rec(title, System.currentTimeMillis())
                jobs[url] = scope.launch {
                    var written = 0L
                    try {
                        written = record(url, title, isLive)
                        android.util.Log.i("TV26", "Aufnahme fertig: $written Bytes")
                    } catch (e: CancellationException) {
                        // Vom Nutzer gestoppt – das ist in Ordnung
                    } catch (e: Exception) {
                        android.util.Log.e("TV26", "Aufnahme-Fehler: ${e.message}")
                        toast(if (written == 0L) tx("Aufnahme fehlgeschlagen: %s", portalHint(e)) else tx("Aufnahme beendet (Verbindung verloren)"))
                    } finally {
                        withContext(Dispatchers.Main) {
                            if (written == 0L) toast(tx("Aufnahme leer – nichts gespeichert. %s", portalHint(null)))
                            jobs.remove(url)
                            active.remove(url)
                            update()
                        }
                    }
                }
            }
            "stop" -> jobs[url]?.cancel()
            "stopall" -> jobs.values.toList().forEach { it.cancel() }
        }
        update()
        return START_NOT_STICKY
    }

    private suspend fun toast(msg: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@RecordService, msg, Toast.LENGTH_LONG).show()
    }

    /** Erklärung, falls der Anbieter nur eine Verbindung erlaubt. */
    private fun portalHint(e: Exception?): String {
        val m = e?.message ?: ""
        return if ("403" in m || "407" in m || "401" in m || "429" in m) {
            tx("Dein Anbieter erlaubt nur eine Verbindung. Du kannst nicht gleichzeitig schauen und aufnehmen.")
        } else {
            tx("Falls dein Anbieter nur eine Verbindung erlaubt, geht Schauen und Aufnehmen nicht gleichzeitig.")
        }
    }

    private fun update() {
        val stopAll = PendingIntent.getService(
            this, 1, Intent(this, RecordService::class.java).setAction("stopall"), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, App.CH_REC)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(tx("Aufnahme läuft (%s)", jobs.size))
            .setContentText(active.values.joinToString { it.title })
            .setOngoing(true)
            .addAction(0, tx("Alle stoppen"), stopAll)
            .build()
        ServiceCompat.startForeground(
            this, 7, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (jobs.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        active.clear()
        super.onDestroy()
    }

    /** Nimmt auf und gibt die Anzahl geschriebener Bytes zurück. */
    private suspend fun record(url: String, title: String, isLive: Boolean): Long {
        val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(40).ifBlank { "REC" }
        val name = "${safe}_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.GERMANY).format(Date())}.ts"
        val sink = openOutput(name)
        var total = 0L
        try {
            sink.stream.use { out ->
                total = if (".m3u8" in url.lowercase()) recordHls(url, out) else recordDirect(url, out, isLive)
            }
        } finally {
            sink.finish(total > 0)
        }
        return total
    }

    /**
     * Direkter Stream (MPEG-TS, MP4 …).
     * Live: läuft bis man selbst stoppt. Trennt der Server die Verbindung (EOF/Fehler), wird neu verbunden.
     * VOD (Film/Folge): endet, wenn der Stream zu Ende ist.
     */
    private suspend fun recordDirect(url: String, out: OutputStream, isLive: Boolean): Long {
        val buf = ByteArray(128 * 1024)
        var total = 0L
        var lastFlush = System.currentTimeMillis()
        var fails = 0
        while (currentCoroutineContext().isActive) {
            var gotData = false
            try {
                Net.open(url, readTimeout = 30_000).use { input ->
                    while (currentCoroutineContext().isActive) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        gotData = true
                        if (System.currentTimeMillis() - lastFlush > 2000) { out.flush(); lastFlush = System.currentTimeMillis() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fails++
                // Noch nie Daten bekommen, oder dauerhaft kaputt -> aufgeben (mit Fehlermeldung)
                if ((total == 0L && fails >= 4) || fails >= 30) throw e
                delay(1500)
                continue
            }
            // Verbindung sauber zu Ende (EOF)
            if (!isLive) break // VOD: Film/Folge fertig
            // Live: der Server hat die Verbindung getrennt -> neu verbinden und weiter aufnehmen
            if (gotData) fails = 0 else fails++
            if (fails >= 30) break
            delay(800)
        }
        out.flush()
        return total
    }

    /** HLS: Playlist immer wieder laden und neue Segmente anhängen. */
    private suspend fun recordHls(url: String, out: OutputStream): Long {
        var media = url
        val seen = LinkedHashSet<String>()
        var total = 0L
        var empty = 0
        while (currentCoroutineContext().isActive) {
            val lines = try {
                Net.text(media, readTimeout = 20_000).lines().map { it.trim() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (total == 0L && empty++ >= 3) throw e
                delay(1500); continue
            }
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                val v = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: break
                media = URL(URL(media), v).toString()
                continue
            }
            val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }?.substringAfter(':')?.toDoubleOrNull() ?: 6.0
            for (l in lines) {
                if (l.isEmpty() || l.startsWith("#")) continue
                val seg = URL(URL(media), l).toString()
                if (seen.add(seg)) {
                    runCatching { Net.open(seg, readTimeout = 20_000).use { total += it.copyTo(out) } }
                    out.flush()
                }
            }
            while (seen.size > 800) seen.remove(seen.first())
            if (lines.any { it.startsWith("#EXT-X-ENDLIST") }) break
            delay((target * 500).toLong().coerceIn(500, 4000))
        }
        return total
    }

    // --- Datei schreiben ---
    private inner class Sink(val stream: OutputStream, val uri: Uri?, val file: File?) {
        /** Datei abschließen: fertige Aufnahme sichtbar machen oder leere Datei löschen. */
        fun finish(keep: Boolean) {
            runCatching {
                if (uri != null && Build.VERSION.SDK_INT >= 29) {
                    if (keep) contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    else contentResolver.delete(uri, null, null)
                } else if (file != null && !keep) {
                    file.delete()
                }
            }
        }
    }

    private fun openOutput(name: String): Sink {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp2t")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TV26")
                // Während der Aufnahme als "in Arbeit" markieren, am Ende freigeben
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IOException(tx("Speicher nicht verfügbar"))
            val out = contentResolver.openOutputStream(uri) ?: throw IOException(tx("Datei kann nicht geschrieben werden"))
            return Sink(out, uri, null)
        }
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "TV26").apply { mkdirs() }
        val f = File(dir, name)
        return Sink(FileOutputStream(f), null, f)
    }
}
