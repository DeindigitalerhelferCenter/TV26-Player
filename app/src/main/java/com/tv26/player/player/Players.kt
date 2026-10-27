@file:OptIn(UnstableApi::class)

package com.tv26.player.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.util.EventLogger
import com.tv26.player.data.Item
import com.tv26.player.data.Kind
import com.tv26.player.data.Net
import com.tv26.player.data.PlayEntry
import com.tv26.player.data.Repo
import com.tv26.player.data.SourceType
import com.tv26.player.data.Stalker

/** Die Liste, die der Player gerade abspielt (Kanäle einer Gruppe, Folgen einer Staffel …). */
object PlayQueue {
    var list: List<PlayEntry> = emptyList()
    var index = 0
    var fromStart = false

    fun start(ctx: Context, entries: List<PlayEntry>, i: Int, fromStart: Boolean = false) {
        if (entries.isEmpty()) return
        list = entries
        index = i.coerceIn(0, entries.size - 1)
        this.fromStart = fromStart
        val intent = Intent(ctx, PlayerActivity::class.java)
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun playLive(ctx: Context, items: List<Item>, i: Int) =
        start(ctx, items.map { PlayEntry(it.name, it.url, it.kind == Kind.LIVE, it, it.logo) }, i)
}

fun buildPlayer(ctx: Context, handleAudioFocus: Boolean = true): ExoPlayer {
    // MAC-Portale liefern Streams oft nur an eine "MAG-Box" aus
    val p = Repo.profile
    val stalker = p?.type == SourceType.STALKER
    val ua = if (stalker && Net.userAgent == Net.DEFAULT_UA) Stalker.UA else Net.userAgent
    // Gleicher Netzwerk-Client wie die App (sicheres DNS, VPN-Tunnel)
    val http = OkHttpDataSource.Factory(
        Net.client.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
    ).setUserAgent(ua)
    val renderers = DefaultRenderersFactory(ctx)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        .setEnableDecoderFallback(true)
    return ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)))
        .setAudioAttributes(AudioAttributes.DEFAULT, handleAudioFocus)
        .build()
        // Diagnose: Zustände, Ladevorgänge und Fehler ins Protokoll (Tag "TV26Exo")
        .also { it.addAnalyticsListener(EventLogger("TV26Exo")) }
}

/** Für das Protokoll: Token, Passwörter und MAC aus Links entfernen. */
fun maskUrl(url: String) = url.replace(Regex("""((?:token|play_token|password|username|mac)=)[^&\s]+""", RegexOption.IGNORE_CASE), "$1***")

fun mediaItem(url: String, title: String): MediaItem = MediaItem.Builder()
    .setUri(url)
    .apply { if (".m3u8" in url.lowercase()) setMimeType(MimeTypes.APPLICATION_M3U8) }
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
    .build()

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
