@file:OptIn(UnstableApi::class)

package com.tv26.player.player

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tv26.player.data.Item
import com.tv26.player.data.Repo
import com.tv26.player.data.tx
import com.tv26.player.ui.Logo
import com.tv26.player.ui.OutlinedTextField
import com.tv26.player.ui.TV26Theme
import com.tv26.player.ui.tvFocus

/** Multi-Screen: 2 oder 4 Kanäle gleichzeitig. Ton kommt vom ausgewählten Bild. */
class MultiActivity : ComponentActivity() {
    val players = arrayOfNulls<ExoPlayer>(4)
    val titles = mutableStateListOf("", "", "", "")
    /** Schwache Geräte (z. B. Fire TV Stick Lite) schaffen nur 2 Videos gleichzeitig */
    var count by mutableIntStateOf(if (com.tv26.player.data.Device.lowEnd) 2 else 4)
    var focused by mutableIntStateOf(0)
    var picking by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { TV26Theme { MultiScreen(this) } }
    }

    fun setChannel(cell: Int, item: Item) {
        val p = players[cell] ?: buildPlayer(this, handleAudioFocus = false).also { players[cell] = it }
        titles[cell] = item.name
        focused = cell
        applyVolume()
        lifecycleScope.launch {
            // MAC-Portal: erst den echten Stream-Link holen
            val url = runCatching { Repo.resolveUrl(item.url) }.getOrNull() ?: return@launch
            p.setMediaItem(mediaItem(url, item.name))
            p.prepare()
            p.play()
        }
    }

    fun applyVolume() {
        players.forEachIndexed { i, p -> p?.volume = if (i == focused) 1f else 0f }
    }

    override fun onStart() {
        super.onStart()
        players.forEach { it?.play() }
    }

    override fun onStop() {
        super.onStop()
        players.forEach { it?.pause() }
    }

    override fun onDestroy() {
        players.forEach { it?.release() }
        super.onDestroy()
    }
}

@Composable
private fun MultiScreen(a: MultiActivity) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { a.finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tx("Zurück"), tint = Color.White) }
            Text(tx("Multi-Screen"), color = Color.White, modifier = Modifier.weight(1f))
            FilterChip(selected = a.count == 2, onClick = { a.count = 2; if (a.focused > 1) a.focused = 0; a.applyVolume() }, label = { Text(tx("2 Bilder")) })
            Spacer(Modifier.width(8.dp))
            if (com.tv26.player.data.Device.lowEnd) {
                Text(tx("Auf diesem Gerät sind höchstens 2 Bilder möglich."), color = Color.Gray, fontSize = 11.sp)
            } else {
                FilterChip(selected = a.count == 4, onClick = { a.count = 4 }, label = { Text(tx("4 Bilder")) })
            }
        }
        if (a.count == 2) {
            Row(Modifier.fillMaxSize()) {
                Cell(a, 0, Modifier.weight(1f))
                Cell(a, 1, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) { Cell(a, 0, Modifier.weight(1f)); Cell(a, 1, Modifier.weight(1f)) }
                Row(Modifier.weight(1f)) { Cell(a, 2, Modifier.weight(1f)); Cell(a, 3, Modifier.weight(1f)) }
            }
        }
    }
    a.picking?.let { cell -> ChannelPicker(onDismiss = { a.picking = null }) { a.setChannel(cell, it); a.picking = null } }
}

@Composable
private fun Cell(a: MultiActivity, i: Int, modifier: Modifier) {
    val title = a.titles[i]
    val isFocused = a.focused == i
    Box(
        modifier.fillMaxHeight().padding(2.dp)
            .border(if (isFocused) 3.dp else 1.dp, if (isFocused) MaterialTheme.colorScheme.primary else Color.DarkGray)
            .tvFocus()
            .clickable {
                if (title.isEmpty()) a.picking = i else { a.focused = i; a.applyVolume() }
            },
    ) {
        if (title.isEmpty()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.AddCircleOutline, null, tint = Color.White, modifier = Modifier.size(40.dp))
                Text(tx("Kanal wählen"), color = Color.White)
            }
        } else {
            AndroidView(
                factory = { c -> PlayerView(c).apply { useController = false } },
                update = { it.player = a.players[i] },
                modifier = Modifier.fillMaxSize(),
            )
            Row(Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isFocused) Icon(Icons.Default.VolumeUp, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(" $title", color = Color.White, fontSize = 12.sp, maxLines = 1)
            }
            IconButton(onClick = { a.picking = i }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.SwapHoriz, tx("Kanal ändern"), tint = Color.White)
            }
        }
    }
}

@Composable
fun ChannelPicker(onDismiss: () -> Unit, onPick: (Item) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = Repo.content.live
    val list = remember(q, all) { all.filter { Repo.visible(it) && (q.isBlank() || it.name.contains(q, true)) }.take(400) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx("Kanal wählen")) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text(tx("Suchen…")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(list) { item ->
                        Row(
                            Modifier.fillMaxWidth().tvFocus().clickable { onPick(item) }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Logo(item.logo, 32.dp)
                            Text(item.name, Modifier.padding(start = 10.dp), maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tx("Schließen")) } },
    )
}
