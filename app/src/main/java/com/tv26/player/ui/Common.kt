@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tv26.player.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tv26.player.data.*
import com.tv26.player.player.PlayQueue
import com.tv26.player.player.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Design ----------

val Accents = listOf(
    "Rot" to Color(0xFFE53935), "Blau" to Color(0xFF1E88E5), "Grün" to Color(0xFF43A047),
    "Orange" to Color(0xFFFB8C00), "Lila" to Color(0xFF8E24AA), "Türkis" to Color(0xFF00ACC1),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TV26Theme(content: @Composable () -> Unit) {
    val accent = Accents[Repo.accent.coerceIn(0, Accents.size - 1)].second
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = accent, secondary = accent, tertiary = accent,
            onPrimary = Color.White, primaryContainer = accent.copy(alpha = 0.3f), onPrimaryContainer = Color.White,
            secondaryContainer = accent.copy(alpha = 0.3f), onSecondaryContainer = Color.White,
            background = Color(0xFF0E1116), surface = Color(0xFF161B22),
            surfaceVariant = Color(0xFF21262D), surfaceContainerHigh = Color(0xFF1C2128),
        ),
    ) {
        val d = LocalDensity.current
        CompositionLocalProvider(
            // Fernbedienung: ausgewählte Knöpfe/Tabs/Chips deutlich in der Akzentfarbe hervorheben
            LocalRippleConfiguration provides RippleConfiguration(
                color = accent,
                rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.38f, hoveredAlpha = 0.14f, pressedAlpha = 0.28f),
            ),
            // Fernseher: etwas größere Schrift (man sitzt weiter weg)
            LocalDensity provides if (Device.isTv) Density(d.density, d.fontScale * 1.12f) else d,
            content = content,
        )
    }
}

/** Menü-Taste ☰ (Fire-TV-/Android-TV-Fernbedienung) öffnet das Menü für diesen Eintrag. */
fun Modifier.menuKey(item: Item): Modifier = this.onPreviewKeyEvent { e ->
    if (e.key != Key.Menu) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyUp) Repo.menuItem = item
    true
}

/** Rahmen, wenn ein Element mit der Fernbedienung (Android-TV) ausgewählt ist. */
fun Modifier.tvFocus(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
    var f by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    this.onFocusChanged { f = it.isFocused || it.hasFocus }
        .then(if (f) Modifier.border(3.dp, color, shape) else Modifier)
}

// ---------- Helfer ----------

fun hm(t: Long): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(t))
fun dayHm(t: Long): String = SimpleDateFormat("EE dd.MM. HH:mm", L.locale).format(Date(t))
fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.Gray)
    }
}

@Composable
fun Logo(url: String, size: Dp = 44.dp, name: String = "") {
    val m = Modifier.size(size).clip(RoundedCornerShape(6.dp))
    if (url.isBlank()) {
        if (name.isNotBlank()) Initials(name, m, size)
        else Box(m.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Tv, null, tint = Color.Gray, modifier = Modifier.size(size * 0.55f))
        }
    } else {
        AsyncImage(model = url, contentDescription = null, modifier = m, contentScale = ContentScale.Fit)
    }
}

/** Logo eines Eintrags: sucht fehlende Bilder automatisch (Quelle, EPG, Logo-Datenbank, TMDB). */
@Composable
fun ItemLogo(item: Item, size: Dp = 44.dp) {
    val v = Art.version + Repo.epgVersion
    val url = remember(item.id, item.logo, v) { Art.url(item) }
    LaunchedEffect(item.id) { Art.request(item) }
    Logo(url, size, item.name)
}

private val tileColors = listOf(0xFF8E24AA, 0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFFE53935, 0xFF00ACC1, 0xFF6D4C41, 0xFF3949AB)

/** Ersatz-Kachel: farbiger Hintergrund mit Anfangsbuchstaben (statt leerem grauem Feld). */
@Composable
fun Initials(name: String, modifier: Modifier, size: Dp = 44.dp) {
    val clean = name.replace(Regex("""^\s*[\p{L}]{2,3}\s*[:|\-]\s*"""), "").trim().ifEmpty { name }
    val letters = clean.split(Regex("""\s+""")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }
    val color = Color(tileColors[(clean.hashCode() and 0x7fffffff) % tileColors.size])
    Box(modifier.background(color.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Text(letters, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).coerceIn(12f, 40f).sp)
    }
}

/** Große Sender-Kachel (Ansicht "groß" bei Live-TV). */
@Composable
fun ChannelTile(item: Item, number: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val v = Repo.epgVersion
    val now = remember(item.id, v) { Epg.nowNext(item).first }
    LaunchedEffect(item.id, Repo.epgReady) { Repo.fetchShortEpg(item) }
    Column(
        modifier.padding(6.dp).tvFocus().clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .menuKey(item).combinedClickable(onClick = onClick, onLongClick = { Repo.menuItem = item }).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ItemLogo(item, 84.dp)
            if (item.id in Repo.favs) Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp).align(Alignment.TopEnd))
        }
        Text("$number  ${item.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
        Text(now?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Color.Gray)
    }
}

/** Kompakte Zeile für Filme/Serien (Ansicht "Liste"). */
@Composable
fun MediaRow(item: Item, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val wv = Repo.watchVersion
    val watched = remember(item.url, wv) { item.url.isNotEmpty() && Repo.store.isWatched(item.url) }
    val v = Art.version
    val url = remember(item.id, item.logo, v) { Art.url(item) }
    LaunchedEffect(item.id) { Art.request(item) }
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp).tvFocus().clip(RoundedCornerShape(8.dp))
            .menuKey(item).combinedClickable(onClick = onClick, onLongClick = { Repo.menuItem = item }).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val m = Modifier.width(46.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(4.dp))
        if (url.isNotBlank()) AsyncImage(url, null, m, contentScale = ContentScale.Crop) else Initials(item.name, m, 46.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val meta = listOfNotNull(
                item.year.ifBlank { null },
                item.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ ${it.take(3)}" },
                item.group,
            )
            Text(meta.joinToString(" · "), fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (item.id in Repo.favs) Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(18.dp))
        if (watched) Icon(Icons.Default.CheckCircle, tx("Gesehen"), tint = Color(0xFF43A047), modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ChannelRow(item: Item, number: Int, onClick: () -> Unit, onInfo: () -> Unit, compact: Boolean = false, modifier: Modifier = Modifier) {
    val v = Repo.epgVersion
    val (now, next) = remember(item.id, v) { Epg.nowNext(item) }
    // Kein Programm in der EPG-Datei? Dann für diesen Kanal direkt beim Server nachfragen.
    LaunchedEffect(item.id, Repo.epgReady) { Repo.fetchShortEpg(item) }
    val fav = item.id in Repo.favs
    Row(
        modifier.fillMaxWidth().padding(vertical = if (compact) 0.dp else 2.dp).tvFocus().clip(RoundedCornerShape(8.dp))
            .menuKey(item).combinedClickable(onClick = onClick, onLongClick = { Repo.menuItem = item })
            .padding(horizontal = 8.dp, vertical = if (compact) 3.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$number", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(36.dp))
        ItemLogo(item, if (compact) 30.dp else 44.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, fontSize = if (compact) 14.sp else 16.sp)
            if (compact) {
                // Liste: nur eine Zeile "jetzt"
                if (now != null) Text("${hm(now.start)}  ${now.title}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.Gray)
            } else if (now != null) {
                Text("${hm(now.start)}  ${now.title}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFCCCCCC))
                val p = ((System.currentTimeMillis() - now.start).toFloat() / (now.stop - now.start)).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(3.dp))
            }
            if (next != null && !compact) Text(tx("Danach: %s  %s", hm(next.start), next.title), fontSize = 11.sp, maxLines = 1, color = Color.Gray)
        }
        if (Catchup.available(item)) Icon(Icons.Default.History, tx("Archiv"), tint = Color.Gray, modifier = Modifier.size(18.dp))
        IconButton(onClick = { Repo.toggleFav(item) }) {
            Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, tx("Favorit"), tint = if (fav) Color(0xFFFFC107) else Color.Gray)
        }
        IconButton(onClick = onInfo) { Icon(Icons.Default.CalendarMonth, tx("TV-Programm"), tint = Color.Gray) }
    }
}

@Composable
fun PosterCard(item: Item, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val wv = Repo.watchVersion
    val watched = remember(item.url, wv) { item.url.isNotEmpty() && Repo.store.isWatched(item.url) }
    val frac = remember(item.url, wv) { if (item.url.isEmpty()) 0f else Repo.store.resumeFraction(item.url) }
    val av = Art.version
    val img = remember(item.id, item.logo, av) { Art.url(item) }
    LaunchedEffect(item.id) { Art.request(item) }
    Column(modifier.padding(6.dp).tvFocus().clip(RoundedCornerShape(8.dp)).menuKey(item).combinedClickable(onClick = onClick, onLongClick = { Repo.menuItem = item })) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (img.isNotBlank()) {
                AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                // Ersatz-Kachel mit Anfangsbuchstaben und Name
                Initials(item.name, Modifier.fillMaxSize(), 80.dp)
                Text(item.name, Modifier.align(Alignment.BottomCenter).padding(6.dp), fontSize = 11.sp, color = Color.White, maxLines = 2)
            }
            if (item.id in Repo.favs) Icon(Icons.Default.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
            if (watched) Icon(Icons.Default.CheckCircle, tx("Gesehen"), tint = Color(0xFF43A047), modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
            if (item.rating.isNotBlank() && item.rating != "0") {
                Text(
                    "★ ${item.rating.take(3)}", fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000)).padding(horizontal = 4.dp),
                )
            }
            if (frac > 0f) LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter))
        }
        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
    }
}

@Composable
fun SimpleRow(item: Item, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).tvFocus().clip(RoundedCornerShape(8.dp)).menuKey(item).combinedClickable(onClick = onClick, onLongClick = { Repo.menuItem = item }).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemLogo(item)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
        }
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(options) { (label, action) ->
                    Text(
                        label,
                        Modifier.fillMaxWidth().tvFocus().clickable { action(); onDismiss() }.padding(14.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tx("Schließen")) } },
    )
}

@Composable
fun PinDialog(onDismiss: () -> Unit, onSuccess: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx("Kinderschutz")) },
        text = {
            Column {
                Text(tx("Diese Gruppe ist gesperrt. Bitte PIN eingeben."))
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8); wrong = false },
                    label = { Text(tx("PIN")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (wrong) Text(tx("Falsche PIN"), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (pin == Repo.store.str("pin")) { Repo.unlocked = true; onSuccess() } else wrong = true
            }) { Text(tx("Öffnen")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx("Abbrechen")) } },
    )
}

/** TV-Programm eines Kanals: Live ansehen, Archiv ansehen, Erinnerung setzen. */
@Composable
fun ProgramDialog(item: Item, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val v = Repo.epgVersion
    LaunchedEffect(item.id, Repo.epgReady) { Repo.fetchShortEpg(item) }
    val now = remember { System.currentTimeMillis() }
    val shown = remember(item.id, v) {
        val from = now - maxOf(item.catchupDays, 0) * DAY
        Epg.programs(item).filter { it.stop > from }
    }
    val startIndex = shown.indexOfFirst { it.stop > now }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 1) },
        text = {
            if (shown.isEmpty()) {
                Text(if (Repo.epgStatus.isNotEmpty()) Repo.epgStatus else tx("Für diesen Kanal gibt es kein TV-Programm."))
            } else {
                LazyColumn(state = state, modifier = Modifier.heightIn(max = 460.dp)) {
                    items(shown) { p ->
                        val live = p.start <= now && p.stop > now
                        val past = p.stop <= now
                        val canCatchup = past && Catchup.available(item)
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp).tvFocus()
                                .background(if (live) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable {
                                    when {
                                        live -> { PlayQueue.playLive(ctx, listOf(item), 0); onDismiss() }
                                        canCatchup -> {
                                            val url = Catchup.url(item, p)
                                            if (url != null) {
                                                PlayQueue.start(ctx, listOf(PlayEntry("${item.name}: ${p.title}", url, false, null, item.logo)), 0, fromStart = true)
                                                onDismiss()
                                            }
                                        }
                                        !past -> {
                                            Reminders.schedule(ctx, item, p)
                                            toast(ctx, tx("Erinnerung gesetzt: %s", p.title))
                                        }
                                    }
                                }
                                .padding(10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${dayHm(p.start)} – ${hm(p.stop)}", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
                                when {
                                    live -> Text(tx("LIVE ▶"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    canCatchup -> Text(tx("Archiv ▶"), fontSize = 12.sp, color = Color(0xFF64B5F6))
                                    !past -> Text(tx("🔔 Erinnern"), fontSize = 12.sp, color = Color.Gray)
                                }
                            }
                            Text(p.title, fontWeight = FontWeight.Medium, color = if (past && !canCatchup) Color.Gray else Color.Unspecified)
                            if (p.desc.isNotBlank()) Text(p.desc, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color.Gray)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tx("Schließen")) } },
    )
}

/** Film-Details mit Abspielen, Fortsetzen, Favorit und externem Player. */
@Composable
fun MovieDialog(item: Item, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val pos = remember { Repo.store.resumePos(item.url) }
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(item.id) {
        val x = Repo.xtream()
        if (x != null && item.streamId.isNotEmpty()) {
            info = runCatching { withContext(Dispatchers.IO) { x.vodInfo(item.streamId) } }.getOrDefault(emptyMap())
        }
    }
    val fav = item.id in Repo.favs
    fun play(fromStart: Boolean) {
        PlayQueue.start(ctx, listOf(PlayEntry(item.name, item.url, false, item, item.logo)), 0, fromStart)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name) },
        text = {
            Row {
                if (item.logo.isNotBlank()) {
                    AsyncImage(
                        model = item.logo, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                    )
                }
                Column(Modifier.padding(start = 12.dp).heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    val meta = listOfNotNull(
                        item.year.ifBlank { info["releasedate"]?.take(4) ?: "" }.ifBlank { null },
                        info["duration"], info["genre"],
                        (item.rating.ifBlank { info["rating"] ?: "" }).takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" },
                    )
                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 13.sp, color = Color.Gray)
                    Text(item.group, fontSize = 12.sp, color = Color.Gray)
                    if (Repo.store.isWatched(item.url)) Text(tx("✓ Gesehen"), color = Color(0xFF43A047), fontSize = 13.sp)
                    val plot = item.plot.ifBlank { info["plot"] ?: "" }
                    if (plot.isNotBlank()) Text(plot, Modifier.padding(top = 8.dp), fontSize = 14.sp)
                    info["director"]?.let { Text(tx("Regie: %s", it), Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Color.Gray) }
                    info["cast"]?.let { Text(tx("Darsteller: %s", it), fontSize = 12.sp, color = Color.Gray) }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = { play(false) }) {
                    Icon(Icons.Default.PlayArrow, null)
                    Text(if (pos > 0) tx("Fortsetzen ab %s", fmtTime(pos)) else tx("Abspielen"))
                }
                if (pos > 0) TextButton(onClick = { play(true) }) { Text(tx("Von vorne")) }
                Row {
                    TextButton(onClick = { Repo.toggleFav(item) }) { Text(if (fav) tx("★ Favorit") else tx("☆ Favorit")) }
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                // MAC-Portal: erst den echten Link holen
                                val url = Repo.resolveUrl(item.url)
                                ctx.startActivity(
                                    android.content.Intent.createChooser(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW)
                                            .setDataAndType(android.net.Uri.parse(url), "video/*"),
                                        tx("Öffnen mit"),
                                    ),
                                )
                            } catch (e: Exception) { toast(ctx, tx("Kein externer Player gefunden")) }
                        }
                    }) { Text(tx("Extern")) }
                    TextButton(onClick = onDismiss) { Text(tx("Schließen")) }
                }
            }
        },
    )
}
