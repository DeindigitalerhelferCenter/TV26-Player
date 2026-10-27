@file:OptIn(UnstableApi::class)

package com.tv26.player.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.tv26.player.data.Epg
import com.tv26.player.data.Kind
import com.tv26.player.data.PlayEntry
import com.tv26.player.data.Repo
import com.tv26.player.data.SourceType
import com.tv26.player.data.tx
import com.tv26.player.ui.ChoiceDialog
import com.tv26.player.ui.ProgramDialog
import com.tv26.player.ui.TV26Theme
import com.tv26.player.ui.hm
import com.tv26.player.ui.tvFocus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerActivity : ComponentActivity() {
    lateinit var player: ExoPlayer
    var index by mutableIntStateOf(0)
    var showUi by mutableStateOf(true)
    var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    var dialog by mutableStateOf<String?>(null)
    var sleepAt by mutableLongStateOf(0L)
    var digits by mutableStateOf("")
    var inPip by mutableStateOf(false)
    var errorText by mutableStateOf<String?>(null)
    var currentUrl by mutableStateOf("")
    /** Schlüssel für "Weiterschauen": der Original-Link (Portal-Links ändern sich bei jedem Abspielen) */
    private var resumeKey = ""
    private var longPressDone = false
    /** Ist der aktuelle Stream schon einmal gelaufen? Erst dann darf die Selbstheilung eingreifen. */
    private var everReady = false
    private var stallRetries = 0
    /** Wie oft der Server die Verbindung getrennt hat und wir neu verbunden haben */
    private var reconnects = 0
    var lastTouch = 0L
    private var sleepJob: Job? = null
    private var digitJob: Job? = null
    private var retries = 0
    private var playJob: Job? = null

    fun entry(): PlayEntry? = PlayQueue.list.getOrNull(index)
    val isLive get() = entry()?.live == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PlayQueue.list.isEmpty()) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        player = buildPlayer(this)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // HTTP 407 = Server meldet "Zugang schon in Benutzung" (oft noch die alte Verbindung)
                val busy = generateSequence(error as Throwable) { it.cause }.any { "407" in (it.message ?: "") }
                // Netzwerkfehler: automatisch mit frischem Link neu verbinden (Live und Filme)
                if (retries < (if (busy) 5 else 3)) {
                    retries++
                    lifecycleScope.launch { delay(if (busy) 3000L else 2000L); retry() }
                } else {
                    errorText = if (busy) tx("Der Server meldet: Zugang wird gerade benutzt (407). Läuft dieselbe MAC oder derselbe Zugang auf einem anderen Gerät?")
                    else tx("Wiedergabe-Fehler: %s", error.errorCodeName)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    errorText = null
                    retries = 0
                    everReady = true
                    // Läuft er wieder stabil, darf die Selbstheilung später erneut helfen
                    lifecycleScope.launch { delay(30_000); if (player.isPlaying) { stallRetries = 0; reconnects = 0 } }
                }
                if (state == Player.STATE_ENDED) {
                    val dur = player.duration
                    val reallyDone = !isLive && dur != C.TIME_UNSET && dur > 0 && player.currentPosition >= dur - 15_000
                    if (!reallyDone && reconnects < 20) {
                        // Der Server hat die Verbindung getrennt (viele IPTV-Server machen das regelmäßig).
                        // Wie VLC & Co.: sofort und still neu verbinden, Filme an derselben Stelle.
                        reconnects++
                        android.util.Log.i("TV26", "Server hat getrennt -> neu verbinden ($reconnects)")
                        retry()
                    } else if (!isLive) {
                        Repo.store.markWatched(resumeKey)
                        // Automatisch die nächste Folge
                        if (index < PlayQueue.list.size - 1) go(index + 1) else finish()
                    }
                }
            }
        })
        index = PlayQueue.index
        play(fromStart = PlayQueue.fromStart)
        setContent { TV26Theme { PlayerScreen(this) } }
        lifecycleScope.launch {
            var bufferingSince = 0L
            while (true) {
                delay(1000)
                if (showUi && dialog == null && player.isPlaying && System.currentTimeMillis() - lastTouch > 5000) showUi = false
                val now = System.currentTimeMillis()
                if (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING && errorText == null) {
                    if (bufferingSince == 0L) bufferingSince = now
                    val waited = now - bufferingSince
                    // Live: schon nach 4 s neu verbinden (der Server liefert dann meist nichts mehr). Filme: nach 10 s.
                    if (everReady && waited > (if (isLive) 3_000 else 10_000) && stallRetries < 10) {
                        // Selbstheilung NUR, wenn der Stream schon lief und dann hängen blieb
                        bufferingSince = 0L
                        stallRetries++
                        android.util.Log.i("TV26", "Stream hängt -> neu verbinden ($stallRetries)")
                        retry()
                    } else if (!everReady && waited > 30_000) {
                        // Start dauert zu lange: klare Meldung statt endlos warten
                        bufferingSince = 0L
                        errorText = tx("Der Stream startet nicht. Bitte erneut versuchen.")
                    }
                } else {
                    bufferingSince = 0L
                }
            }
        }
        // MAC-Portal: oft "ich bin noch da" melden, damit der Server die Verbindung länger offen lässt
        if (Repo.profile?.type == SourceType.STALKER) {
            lifecycleScope.launch {
                while (true) {
                    Repo.keepAlive()
                    delay(10_000)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveResume()
        index = PlayQueue.index
        everReady = false
        stallRetries = 0
        reconnects = 0
        play(fromStart = PlayQueue.fromStart)
    }

    fun play(fromStart: Boolean = false, url: String? = null) {
        val e = entry() ?: return finish()
        // VPN-Schutz: ohne verbundenes VPN nicht abspielen (sonst wäre die echte IP sichtbar)
        if (com.tv26.player.data.Vpn.required && com.tv26.player.data.Vpn.state != com.tv26.player.data.Vpn.State.ON) {
            player.stop()
            errorText = tx("VPN ist nicht verbunden. Zum Schutz deiner IP wird nichts abgespielt.")
            return
        }
        errorText = null
        // Verlauf: Kanäle, Filme und Serien
        e.item?.let { Repo.addRecent(it); Repo.lastPlayedId = it.id }
        resumeKey = url ?: e.url
        showUi = true
        lastTouch = System.currentTimeMillis()
        playJob?.cancel()
        playJob = lifecycleScope.launch {
            // MAC-Portal: erst den echten Stream-Link holen
            currentUrl = try {
                Repo.resolveUrl(url ?: e.url)
            } catch (ex: Exception) {
                errorText = tx("Wiedergabe-Fehler: %s", ex.message)
                return@launch
            }
            player.setMediaItem(mediaItem(currentUrl, e.title))
            if (!e.live && !fromStart && url == null) {
                val pos = Repo.store.resumePos(resumeKey)
                if (pos > 0) player.seekTo(pos)
            }
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** Neu verbinden. Portal-Links werden dabei neu erzeugt, weil sie nur kurz gültig sind. */
    fun retry() {
        // Position merken: Filme/Serien laufen an derselben Stelle weiter.
        // play() holt bei MAC-Portalen auch einen frischen Link (die alten sind nur kurz gültig).
        saveResume()
        play()
    }

    fun go(newIndex: Int) {
        saveResume()
        retries = 0
        everReady = false
        stallRetries = 0
        reconnects = 0
        index = newIndex
        play()
    }

    fun switch(delta: Int) {
        val n = PlayQueue.list.size
        if (n > 1) go((index + delta).mod(n))
    }

    fun seekBy(ms: Long) {
        if (!isLive || player.isCurrentMediaItemSeekable) player.seekTo((player.currentPosition + ms).coerceAtLeast(0))
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun saveResume() {
        val e = entry() ?: return
        if (!e.live && resumeKey.isNotEmpty() && player.duration > 0) {
            Repo.store.saveResume(resumeKey, player.currentPosition, player.duration)
        }
    }

    fun setSleep(min: Int) {
        sleepJob?.cancel()
        if (min == 0) { sleepAt = 0; return }
        sleepAt = System.currentTimeMillis() + min * 60_000L
        sleepJob = lifecycleScope.launch {
            delay(min * 60_000L)
            player.pause()
            finish()
        }
    }

    fun toggleRecord() {
        val e = entry() ?: return
        if (currentUrl in RecordService.active) {
            RecordService.stop(this, currentUrl)
            Toast.makeText(this, tx("Aufnahme gestoppt"), Toast.LENGTH_SHORT).show()
        } else {
            RecordService.start(this, currentUrl, e.title, e.live)
            Toast.makeText(this, tx("Aufnahme gestartet (Download/TV26)"), Toast.LENGTH_LONG).show()
        }
    }

    fun openExternal() {
        player.pause()
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(currentUrl), "video/*")
        try {
            startActivity(Intent.createChooser(i, tx("Öffnen mit")))
        } catch (e: Exception) {
            Toast.makeText(this, tx("Kein externer Player gefunden"), Toast.LENGTH_SHORT).show()
        }
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            val size = player.videoSize
            val ratio = if (size.width > 0 && size.height > 0) {
                Rational(size.width, size.height).let { r ->
                    // Android erlaubt nur Verhältnisse zwischen 1:2,39 und 2,39:1
                    if (r.toFloat() > 2.39f || r.toFloat() < 0.42f) Rational(16, 9) else r
                }
            } else Rational(16, 9)
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
        }
    }

    fun trackOptions(type: Int): List<Pair<String, () -> Unit>> {
        val out = mutableListOf<Pair<String, () -> Unit>>()
        val params = player.trackSelectionParameters
        if (type == C.TRACK_TYPE_TEXT) {
            out += tx("Aus") to { player.trackSelectionParameters = params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
        }
        player.currentTracks.groups.filter { it.type == type }.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val parts = listOfNotNull(
                    f.label, f.language?.uppercase(),
                    if (f.channelCount > 0) tx("%s Kan.", f.channelCount) else null,
                    f.sampleMimeType?.substringAfter('/'),
                )
                val sel = if (g.isTrackSelected(i)) "✓ " else ""
                out += (sel + parts.joinToString(" · ").ifBlank { tx("Spur %s", out.size + 1) }) to {
                    player.trackSelectionParameters = params.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        return out
    }

    fun infoText(): String = buildString {
        val v = player.videoFormat
        val a = player.audioFormat
        appendLine(tx("Titel: %s", entry()?.title))
        appendLine("URL: $currentUrl")
        if (v != null) {
            append("${tx("Video")}: ${v.width}×${v.height}")
            v.sampleMimeType?.let { append(" · ${it.substringAfter('/')}") }
            if (v.frameRate > 0) append(" · ${v.frameRate.roundToInt()} fps")
            if (v.bitrate > 0) append(" · ${v.bitrate / 1000} kbit/s")
            appendLine()
        }
        if (a != null) {
            append("${tx("Audio")}: ${a.sampleMimeType?.substringAfter('/')}")
            if (a.channelCount > 0) append(" · " + tx("%s Kanäle", a.channelCount))
            if (a.sampleRate > 0) append(" · ${a.sampleRate} Hz")
            if (a.bitrate > 0) append(" · ${a.bitrate / 1000} kbit/s")
            appendLine()
        }
        appendLine(tx("Puffer: %s s", player.totalBufferedDuration / 1000))
        if (player.duration > 0) appendLine(tx("Länge: %s", fmtTime(player.duration)))
    }

    /** Favorit an/aus für den laufenden Kanal, Film oder die Serie. */
    fun toggleFavorite() {
        val item = entry()?.item ?: return
        Repo.toggleFav(item)
        val msg = if (item.id in Repo.favs) tx("Zu Favoriten hinzugefügt") else tx("Aus Favoriten entfernt")
        Toast.makeText(this, "★ $msg: ${item.name}", Toast.LENGTH_SHORT).show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastTouch = System.currentTimeMillis()
        // OK lange halten = Favorit (Fernbedienung)
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) longPressDone = false
            if (event.action == KeyEvent.ACTION_DOWN && !longPressDone && (event.isLongPress || event.repeatCount >= 8)) {
                longPressDone = true
                toggleFavorite()
                return true
            }
            // Wiederholungen und Loslassen nach dem langen Drücken nicht als Klick werten
            if (longPressDone) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !showUi) {
            // Live-TV: OK öffnet die Kanalliste (Fire-TV-Fernbedienung hat keine Zahlentasten). Sonst Werkzeuge zeigen.
            if (isLive && PlayQueue.list.size > 1) dialog = "channels" else showUi = true
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { switch(1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { switch(-1); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (!showUi && PlayQueue.list.size > 1) { switch(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!showUi && PlayQueue.list.size > 1) { switch(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!showUi) { seekBy(-10_000); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!showUi) { seekBy(10_000); return true }
            // OK: die Aktion kommt beim Loslassen (onKeyUp), damit "lange halten = Favorit" nicht stört
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (!showUi) return true
            // Menü-Taste ☰: Werkzeuge ein-/ausblenden
            KeyEvent.KEYCODE_MENU -> { showUi = !showUi; return true }
            // Chromebook/Tastatur: Esc wie Zurück
            KeyEvent.KEYCODE_ESCAPE -> { if (showUi && dialog == null) showUi = false else finish(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { switch(1); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { switch(-1); return true }
            KeyEvent.KEYCODE_INFO -> { dialog = "info"; return true }
            KeyEvent.KEYCODE_GUIDE -> if (entry()?.item?.kind == Kind.LIVE) { dialog = "epg"; return true }
            KeyEvent.KEYCODE_BACK -> if (showUi && dialog == null) { showUi = false; return true }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (PlayQueue.list.size > 1) {
                // Kanal-Nummer eingeben, nach 2 Sekunden umschalten
                digits = (digits + (keyCode - KeyEvent.KEYCODE_0)).takeLast(4)
                digitJob?.cancel()
                digitJob = lifecycleScope.launch {
                    delay(2000)
                    val n = digits.toIntOrNull() ?: 0
                    digits = ""
                    if (n in 1..PlayQueue.list.size) go(n - 1)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Repo.store.bool("pip_auto", true) && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) showUi = false
    }

    override fun onStop() {
        super.onStop()
        if (!::player.isInitialized) return
        saveResume()
        // Im Bild-im-Bild-Modus läuft das Video weiter
        if (!inPip) player.pause()
    }

    override fun onDestroy() {
        if (::player.isInitialized) {
            saveResume()
            player.release()
        }
        sleepJob?.cancel()
        super.onDestroy()
    }
}

@Composable
private fun PlayerScreen(a: PlayerActivity) {
    val player = a.player
    var feedback by remember { mutableStateOf<String?>(null) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    val subSize = remember { Repo.store.float("sub_size", 1f) }
    val subColor = remember { runCatching { android.graphics.Color.parseColor(Repo.store.str("sub_color", "#FFFFFF")) }.getOrDefault(android.graphics.Color.WHITE) }

    LaunchedEffect(Unit) {
        while (true) {
            pos = player.currentPosition
            dur = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            delay(500)
        }
    }
    LaunchedEffect(feedback) {
        if (feedback != null) { delay(1000); feedback = null }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    this.player = player
                    // Beim Neuverbinden das letzte Bild stehen lassen statt schwarz
                    setKeepContentOnPlayerReset(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    subtitleView?.apply {
                        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * subSize)
                        setStyle(
                            CaptionStyleCompat(
                                subColor, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null,
                            ),
                        )
                    }
                }
            },
            update = { it.resizeMode = a.resizeMode },
            modifier = Modifier.fillMaxSize(),
        )

        // Gesten: Tippen = Menü, Doppeltippen = ±10 s, Wischen links = Helligkeit, rechts = Lautstärke, quer = Spulen
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { a.showUi = !a.showUi },
                        // Lange drücken = Favorit (Touch)
                        onLongPress = { a.toggleFavorite() },
                        onDoubleTap = { o ->
                            val d = if (o.x < size.width / 2) -10_000L else 10_000L
                            a.seekBy(d)
                            feedback = if (d < 0) "« 10 s" else "10 s »"
                        },
                    )
                }
                .pointerInput(Unit) {
                    val am = a.getSystemService(AudioManager::class.java)
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    var mode = 0
                    var acc = Offset.Zero
                    var startX = 0f
                    var startVol = 0
                    var startBright = 0.5f
                    var seekBase = 0L
                    var seekDelta = 0L
                    detectDragGestures(
                        onDragStart = { o ->
                            mode = 0; acc = Offset.Zero; startX = o.x; seekDelta = 0
                            startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                            startBright = a.window.attributes.screenBrightness.let { if (it < 0) 0.5f else it }
                            seekBase = player.currentPosition
                        },
                        onDragEnd = {
                            if (mode == 3 && seekDelta != 0L) player.seekTo((seekBase + seekDelta).coerceAtLeast(0))
                            mode = 0
                        },
                        onDrag = { change, d ->
                            change.consume()
                            acc += d
                            if (mode == 0 && acc.getDistance() > 30f) {
                                mode = if (abs(acc.x) > abs(acc.y)) 3 else if (startX < size.width / 2) 1 else 2
                            }
                            val h = size.height.toFloat()
                            when (mode) {
                                1 -> {
                                    val b = (startBright - acc.y / h).coerceIn(0.01f, 1f)
                                    a.window.attributes = a.window.attributes.apply { screenBrightness = b }
                                    feedback = tx("☀ Helligkeit %s %%", (b * 100).toInt())
                                }
                                2 -> {
                                    val v = (startVol - acc.y / h * maxVol * 1.5f).roundToInt().coerceIn(0, maxVol)
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    feedback = tx("🔊 Lautstärke %s %%", v * 100 / maxVol.coerceAtLeast(1))
                                }
                                3 -> if (!a.isLive) {
                                    seekDelta = (acc.x / size.width * 180_000).toLong()
                                    val target = (seekBase + seekDelta).coerceAtLeast(0)
                                    feedback = "${fmtTime(target)}  (${if (seekDelta >= 0) "+" else "-"}${fmtTime(abs(seekDelta))})"
                                }
                            }
                        },
                    )
                },
        )

        if (buffering && a.errorText == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

        feedback?.let {
            Text(
                it, color = Color.White, fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(10.dp)).padding(16.dp),
            )
        }

        a.errorText?.let { err ->
            Column(
                Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(err, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { a.retry() }) { Text(tx("Erneut versuchen")) }
                    OutlinedButton(onClick = { a.openExternal() }) { Text(tx("Extern öffnen"), color = Color.White) }
                }
            }
        }

        if (a.digits.isNotEmpty()) {
            Text(
                a.digits, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(12.dp),
            )
        }

        // Aufnahme-Anzeige: immer sichtbar (auch ohne Steuerung), blinkt, mit laufender Zeit – bis man selbst stoppt
        val rec = RecordService.active[a.currentUrl]
        if (rec != null && !a.inPip) {
            var blink by remember { mutableStateOf(true) }
            var recNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(600); blink = !blink; recNow = System.currentTimeMillis() } }
            Row(
                Modifier.align(Alignment.TopEnd).padding(20.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(20.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(12.dp).background(if (blink) Color.Red else Color.Transparent, CircleShape))
                Text("  REC  " + fmtTime(recNow - rec.startedAt), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        AnimatedVisibility(a.showUi && !a.inPip, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Controls(a, pos, dur, playing)
        }

        val item = a.entry()?.item
        when (a.dialog) {
            "audio" -> ChoiceDialog(tx("Audiospur"), a.trackOptions(C.TRACK_TYPE_AUDIO).ifEmpty { listOf(tx("Keine Auswahl möglich") to {}) }) { a.dialog = null }
            "sub" -> ChoiceDialog(tx("Untertitel"), a.trackOptions(C.TRACK_TYPE_TEXT)) { a.dialog = null }
            "format" -> ChoiceDialog(
                tx("Bildformat"),
                listOf(
                    tx("Anpassen") to AspectRatioFrameLayout.RESIZE_MODE_FIT,
                    tx("Strecken (Vollbild)") to AspectRatioFrameLayout.RESIZE_MODE_FILL,
                    tx("Zoom (Zuschneiden)") to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
                    tx("Volle Breite") to AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
                    tx("Volle Höhe") to AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
                ).map { (label, mode) -> (if (a.resizeMode == mode) "✓ $label" else label) to { a.resizeMode = mode } },
            ) { a.dialog = null }
            "speed" -> ChoiceDialog(
                tx("Geschwindigkeit"),
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { s ->
                    (if (player.playbackParameters.speed == s) "✓ ${s}x" else "${s}x") to { player.setPlaybackSpeed(s) }
                },
            ) { a.dialog = null }
            "timer" -> ChoiceDialog(
                tx("Sleep-Timer"),
                listOf(0, 15, 30, 45, 60, 90, 120).map { m -> (if (m == 0) tx("Aus") else tx("%s Minuten", m)) to { a.setSleep(m) } },
            ) { a.dialog = null }
            "info" -> AlertDialog(
                onDismissRequest = { a.dialog = null },
                title = { Text(tx("Stream-Info")) },
                text = { Text(a.infoText()) },
                confirmButton = { TextButton(onClick = { a.dialog = null }) { Text(tx("OK")) } },
            )
            "epg" -> if (item != null) {
                ProgramDialog(item) { a.dialog = null }
            } else {
                LaunchedEffect(Unit) { a.dialog = null }
            }
            "channels" -> ChannelListDialog(a) { a.dialog = null }
        }
    }
}

/** Kanalliste im Player (OK bei Live-TV). Der laufende Kanal ist ausgewählt; OK schaltet um. */
@Composable
private fun ChannelListDialog(a: PlayerActivity, onDismiss: () -> Unit) {
    val list = PlayQueue.list
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (a.index - 3).coerceAtLeast(0))
    val current = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { current.requestFocus() } }
    val epgV = Repo.epgVersion
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx("Kanäle")) },
        text = {
            LazyColumn(state = state, modifier = Modifier.heightIn(max = 460.dp)) {
                itemsIndexed(list) { i, e ->
                    val now = remember(e.item?.id, epgV) { e.item?.let { Epg.nowNext(it).first } }
                    Row(
                        (if (i == a.index) Modifier.focusRequester(current) else Modifier)
                            .fillMaxWidth().tvFocus()
                            .background(if (i == a.index) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { onDismiss(); if (i != a.index) a.go(i) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", Modifier.width(40.dp), color = Color.Gray, fontSize = 12.sp)
                        e.item?.let { com.tv26.player.ui.ItemLogo(it, 32.dp) }
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (i == a.index) FontWeight.Bold else FontWeight.Normal)
                            now?.let { Text("${hm(it.start)}  ${it.title}", fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tx("Schließen")) } },
    )
}

@Composable
private fun Controls(a: PlayerActivity, pos: Long, dur: Long, playing: Boolean) {
    val e = a.entry() ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val epgV = Repo.epgVersion
    val nowNext = remember(e, epgV, pos / 60_000) { e.item?.let { Epg.nowNext(it) } }
    LaunchedEffect(e, Repo.epgReady) { e.item?.let { Repo.fetchShortEpg(it) } }
    val queue = PlayQueue.list.size

    Box(Modifier.fillMaxSize()) {
        // Oben: Titel und Werkzeuge
        Column(
            Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color(0xDD000000), Color.Transparent)))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { a.finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tx("Zurück"), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (e.live && queue > 1) "${a.index + 1}  " else "") + e.title,
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    nowNext?.first?.let { Text(tx("Jetzt: %s–%s  %s", hm(it.start), hm(it.stop), it.title), color = Color(0xFFDDDDDD), fontSize = 13.sp, maxLines = 1) }
                    nowNext?.second?.let { Text(tx("Danach: %s  %s", hm(it.start), it.title), color = Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 1) }
                }
                // REC-Anzeige ist das immer sichtbare Overlay oben rechts (nicht mehr hier doppelt)
                if (a.sleepAt > 0) {
                    Text(tx("⏱ %s min", (a.sleepAt - System.currentTimeMillis()) / 60_000 + 1), color = Color.White)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                e.item?.let { item ->
                    val fav = item.id in Repo.favs
                    Tool(if (fav) Icons.Default.Star else Icons.Default.StarBorder, tx("Favorit"), tint = if (fav) Color(0xFFFFC107) else Color.White) {
                        a.toggleFavorite()
                    }
                }
                Tool(Icons.Default.Audiotrack, tx("Audio")) { a.dialog = "audio" }
                Tool(Icons.Default.Subtitles, tx("Untertitel")) { a.dialog = "sub" }
                Tool(Icons.Default.AspectRatio, tx("Format")) { a.dialog = "format" }
                if (!e.live) Tool(Icons.Default.Speed, tx("Tempo")) { a.dialog = "speed" }
                Tool(Icons.Default.Timer, tx("Timer")) { a.dialog = "timer" }
                if (e.live && queue > 1) Tool(Icons.Default.List, tx("Kanäle")) { a.dialog = "channels" }
                if (e.item?.kind == Kind.LIVE) Tool(Icons.Default.CalendarMonth, tx("Programm")) { a.dialog = "epg" }
                Tool(Icons.Default.FiberManualRecord, if (a.currentUrl in RecordService.active) tx("Stopp REC") else tx("Aufnahme")) { a.toggleRecord() }
                // Bild-im-Bild nur, wenn das Gerät es kann (Fire TV z. B. nicht)
                if (com.tv26.player.data.Device.hasPip) Tool(Icons.Default.PictureInPictureAlt, tx("Bild-im-Bild")) { a.enterPip() }
                Tool(Icons.Default.OpenInNew, tx("Extern")) { a.openExternal() }
                Tool(Icons.Default.Info, tx("Info")) { a.dialog = "info" }
            }
        }

        // Mitte: Steuerung
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (queue > 1) BigButton(Icons.Default.SkipPrevious, tx("Zurück")) { a.switch(-1) }
            if (!e.live) BigButton(Icons.Default.FastRewind, "-10 s") { a.seekBy(-10_000) }
            BigButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", big = true, modifier = Modifier.focusRequester(focus)) { a.togglePlay() }
            if (!e.live) BigButton(Icons.Default.FastForward, "+10 s") { a.seekBy(10_000) }
            if (queue > 1) BigButton(Icons.Default.SkipNext, tx("Weiter")) { a.switch(1) }
        }

        // Unten: Zeitleiste bei Filmen/Serien
        if (!e.live && dur > 0) {
            var dragging by remember { mutableStateOf<Float?>(null) }
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Slider(
                    value = dragging ?: (pos.toFloat() / dur).coerceIn(0f, 1f),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { a.player.seekTo((it * dur).toLong()) }
                        dragging = null
                    },
                )
                Row {
                    Text(fmtTime(dragging?.let { (it * dur).toLong() } ?: pos), color = Color.White)
                    Spacer(Modifier.weight(1f))
                    Text("-" + fmtTime(dur - pos) + "  /  " + fmtTime(dur), color = Color.White)
                }
            }
        } else if (e.live) {
            Text(
                tx("LIVE") + (if (queue > 1) tx("  ·  ▲▼ Kanal wechseln") else "") +
                    // Fernseher: OK öffnet die Kanalliste. Handy/Tablet: Zahlen-Eingabe (falls Tastatur)
                    (if (queue > 1) (if (com.tv26.player.data.Device.isTv) tx("  ·  OK = Kanalliste") else tx("  ·  Zahlen = Kanal-Nummer")) else "") +
                    (if (e.item != null) tx("  ·  OK halten = Favorit") else ""),
                color = Color(0xFFEEEEEE), fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun Tool(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        Modifier.tvFocus().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint)
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun BigButton(icon: ImageVector, label: String, big: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val s = if (big) 72.dp else 52.dp
    Box(
        modifier.size(s).tvFocus(CircleShape).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(s * 0.6f))
    }
}
