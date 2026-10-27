@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.tv26.player.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tv26.player.data.*
import com.tv26.player.player.MultiActivity
import com.tv26.player.player.PlayQueue
import com.tv26.player.player.fmtTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class HomeTab(val label: String, val icon: ImageVector) {
    LIVE("Live-TV", Icons.Default.LiveTv),
    MOVIES("Filme", Icons.Default.Movie),
    SERIES("Serien", Icons.Default.VideoLibrary),
    FAVS("Favoriten", Icons.Default.Star),
    HISTORY("Verlauf", Icons.Default.History),
    SEARCH("Suche", Icons.Default.Search),
    GUIDE("TV-Guide", Icons.Default.CalendarMonth),
    SETTINGS("Einstellungen", Icons.Default.Settings),
}

private const val ALL = "\u0000Alle"
private const val FAV = "\u0000Favoriten"
private const val RECENT = "\u0000Zuletzt gesehen"

@Composable
fun HomeScreen() {
    val p = Repo.profile ?: return
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIVE) }
    var movie by remember { mutableStateOf<Item?>(null) }
    var series by remember { mutableStateOf<Item?>(null) }
    var program by remember { mutableStateOf<Item?>(null) }
    var askPin by remember { mutableStateOf(false) }
    val tabFocus = remember { FocusRequester() }

    // Zurück verlässt die App, die Playlist bleibt offen (Wechseln geht über den Knopf oben rechts)
    BackHandler {
        if (series != null) series = null else (ctx as? android.app.Activity)?.finish()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TV26", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text("  ${p.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), color = Color.Gray)
            // Schloss: mit PIN gesperrte (18+) Inhalte zeigen bzw. wieder verstecken
            if (Repo.hasLocks) {
                IconButton(onClick = { if (Repo.unlocked) Repo.unlocked = false else askPin = true }) {
                    Icon(
                        if (Repo.unlocked) Icons.Default.LockOpen else Icons.Default.Lock, tx("Kinderschutz"),
                        tint = if (Repo.unlocked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            }
            // Ansicht umschalten: groß -> mittel -> Liste (gilt für Live-TV, Filme, Serien)
            IconButton(onClick = { Repo.cycleViewMode() }) {
                Icon(
                    when (Repo.viewMode) {
                        0 -> Icons.Default.ViewModule
                        1 -> Icons.Default.ViewComfy
                        else -> Icons.AutoMirrored.Filled.ViewList
                    },
                    tx("Ansicht ändern"),
                )
            }
            IconButton(onClick = { ctx.startActivity(Intent(ctx, MultiActivity::class.java)) }) { Icon(Icons.Default.GridView, tx("Multi-Screen")) }
            IconButton(onClick = { Repo.reload() }) { Icon(Icons.Default.Refresh, tx("Neu laden")) }
            IconButton(onClick = { Repo.close() }) { Icon(Icons.AutoMirrored.Filled.Logout, tx("Playlist wechseln")) }
        }
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            HomeTab.entries.forEach { ht ->
                Tab(
                    selected = tab == ht,
                    onClick = { tab = ht; series = null },
                    text = { Text(tx(ht.label)) },
                    icon = { Icon(ht.icon, null) },
                    modifier = if (tab == ht) Modifier.focusRequester(tabFocus) else Modifier,
                )
            }
        }
        // Fernbedienung: "Hoch" aus dem Inhalt führt immer zum aktuellen Tab
        // (sonst springt der Fokus an den Tabs vorbei zu den Knöpfen ganz oben)
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .focusProperties { exit = { dir -> if (dir == FocusDirection.Up) tabFocus else FocusRequester.Default } }
                .focusGroup(),
        ) {
            val err = Repo.error
            val s = series
            when {
                // Einstellungen sind auch während des Ladens erreichbar (z. B. um VPN/DNS zu ändern)
                Repo.loading && tab == HomeTab.SETTINGS -> SettingsTab()
                Repo.loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(Repo.status, Modifier.padding(top = 12.dp))
                }
                err != null && tab != HomeTab.SETTINGS -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(tx("Fehler beim Laden"), fontWeight = FontWeight.Bold)
                    Text(err, color = Color.Gray, modifier = Modifier.padding(8.dp))
                    Button(onClick = { Repo.reload() }) { Text(tx("Erneut versuchen")) }
                }
                s != null -> SeriesDetail(s) { series = null }
                else -> when (tab) {
                    HomeTab.LIVE -> LiveTab { program = it }
                    HomeTab.MOVIES -> WithBgStatus { MovieGrid(Kind.MOVIE, Repo.content.movies, tx("Keine Filme in dieser Playlist")) { movie = it } }
                    HomeTab.SERIES -> WithBgStatus { MovieGrid(Kind.SERIES, Repo.content.series, tx("Keine Serien")) { series = it } }
                    HomeTab.FAVS -> FavTab(onMovie = { movie = it }, onSeries = { series = it }, onProgram = { program = it })
                    HomeTab.HISTORY -> HistoryTab(onMovie = { movie = it }, onSeries = { series = it })
                    HomeTab.SEARCH -> SearchTab(onMovie = { movie = it }, onSeries = { series = it })
                    HomeTab.GUIDE -> GuideTab { program = it }
                    HomeTab.SETTINGS -> SettingsTab()
                }
            }
        }
    }

    movie?.let { MovieDialog(it) { movie = null } }
    Repo.menuItem?.let { ItemMenu(it) { Repo.menuItem = null } }
    if (askPin) PinDialog(onDismiss = { askPin = false }) { askPin = false }
    program?.let { ProgramDialog(it) { program = null } }
}

/** Gruppen (Kategorien) links oder oben, Inhalt rechts. Mit Favoriten, Verlauf, Ausblenden und Kinderschutz. */
@Composable
fun GroupBrowser(
    kind: Kind,
    items: List<Item>,
    /** Kategorien, die es schon gibt, auch wenn ihr Inhalt noch nicht geladen ist (MAC-Portal) */
    extraGroups: List<String> = emptyList(),
    /** Wird aufgerufen, wenn eine Kategorie gewählt wird (null = "Alle") */
    onSelect: (String?) -> Unit = {},
    body: @Composable (List<Item>) -> Unit,
) {
    val groups = remember(items, extraGroups, Repo.hidden) {
        (extraGroups + items.map { it.group }).distinct().filter { Repo.groupKey(kind, it) !in Repo.hidden }
    }
    val all = listOf(ALL, FAV) + (if (kind == Kind.LIVE) listOf(RECENT) else emptyList()) + groups
    var sel by rememberSaveable(kind) { mutableStateOf(ALL) }
    var pinFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sel) {
        when (sel) {
            ALL -> onSelect(null)
            FAV, RECENT -> Unit
            else -> onSelect(sel)
        }
    }
    val filtered = remember(sel, items, Repo.favs, Repo.recent, Repo.hidden, Repo.locked, Repo.unlocked, Repo.hiddenItems, Repo.lockedItems) {
        when (sel) {
            ALL -> items.filter { Repo.visible(it) }
            // Favoriten/Verlauf auch dann zeigen, wenn ihre Kategorie noch nicht geladen ist
            FAV -> Repo.favs.mapNotNull { Repo.lookup(it) }.filter { it.kind == kind }
            RECENT -> Repo.recent.mapNotNull { Repo.lookup(it) }.filter { it.kind == kind && Repo.visible(it) }
            // Gruppe ist schon per PIN geöffnet; einzeln ausgeblendete/gesperrte Einträge trotzdem beachten
            else -> items.filter { it.group == sel && it.id !in Repo.hiddenItems && (Repo.unlocked || it.id !in Repo.lockedItems) }
        }
    }
    fun select(g: String) {
        if (g !in listOf(ALL, FAV, RECENT) && Repo.isLocked(kind, g)) pinFor = g else sel = g
    }
    fun label(g: String) = if (g.startsWith("\u0000")) tx(g.drop(1)) else g

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > 700.dp) {
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(250.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface), contentPadding = PaddingValues(6.dp)) {
                    items(all) { g ->
                        val selected = g == sel
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 1.dp).tvFocus().clip(RoundedCornerShape(8.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
                                .clickable { select(g) }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label(g), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                            if (Repo.isLocked(kind, g)) Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = Color.Gray)
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { body(filtered) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(all) { g ->
                        FilterChip(
                            selected = g == sel, onClick = { select(g) },
                            label = { Text(label(g), maxLines = 1) },
                            leadingIcon = if (Repo.isLocked(kind, g)) { { Icon(Icons.Default.Lock, null, Modifier.size(14.dp)) } } else null,
                        )
                    }
                }
                Box(Modifier.weight(1f)) { body(filtered) }
            }
        }
    }

    pinFor?.let { g ->
        PinDialog(onDismiss = { pinFor = null }) { sel = g; pinFor = null }
    }
}

@Composable
private fun LiveTab(onProgram: (Item) -> Unit) {
    val ctx = LocalContext.current
    val target = remember { FocusRequester() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    GroupBrowser(Kind.LIVE, Repo.content.live) { list ->
        // Fernseher: beim Öffnen und beim Zurückkommen aus dem Player den zuletzt geschauten Kanal auswählen
        // (sonst den ersten). Ohne das wäre auf dem TV nichts ausgewählt.
        var focusIdx by remember { mutableIntStateOf(0) }
        val listState = rememberLazyListState()
        val gridState = rememberLazyGridState()
        LaunchedEffect(lifecycle, list) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (!Device.isTv || list.isEmpty()) return@repeatOnLifecycle
                val i = list.indexOfFirst { it.id == Repo.lastPlayedId }.coerceAtLeast(0)
                focusIdx = i
                if (Repo.viewMode == 0) gridState.scrollToItem(i) else listState.scrollToItem(i)
                delay(250)
                runCatching { target.requestFocus() }
            }
        }
        if (list.isEmpty()) Empty(tx("Keine Kanäle"))
        else if (Repo.viewMode == 0) {
            // Groß: Kacheln mit großem Logo
            LazyVerticalGrid(GridCells.Adaptive(150.dp), state = gridState, contentPadding = PaddingValues(8.dp)) {
                gridItemsIndexed(list, key = { i, it -> "$i:${it.id}" }) { i, item ->
                    ChannelTile(item, i + 1, if (i == focusIdx) Modifier.focusRequester(target) else Modifier) { PlayQueue.playLive(ctx, list, i) }
                }
            }
        } else LazyColumn(state = listState, contentPadding = PaddingValues(8.dp)) {
            // Mittel: Zeile mit Jetzt/Danach. Liste: kompakte Zeile.
            itemsIndexed(list, key = { i, it -> "$i:${it.id}" }) { i, item ->
                ChannelRow(
                    item, i + 1, onClick = { PlayQueue.playLive(ctx, list, i) }, onInfo = { onProgram(item) },
                    compact = Repo.viewMode == 2, modifier = if (i == focusIdx) Modifier.focusRequester(target) else Modifier,
                )
            }
        }
    }
}

/** Zeigt oben einen Hinweis, solange Filme/Serien noch im Hintergrund laden. */
@Composable
private fun WithBgStatus(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (Repo.vodBusy > 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

/** Verlauf: alles, was angesehen wurde. Einzeln oder komplett löschbar. */
@Composable
private fun HistoryTab(onMovie: (Item) -> Unit, onSeries: (Item) -> Unit) {
    val ctx = LocalContext.current
    val c = Repo.content
    val items = remember(c, Repo.recent) { Repo.recent.mapNotNull { Repo.lookup(it) }.filter { Repo.visible(it) } }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tx("Verlauf"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) {
                OutlinedButton(onClick = { confirm = true }, modifier = Modifier.tvFocus()) {
                    Icon(Icons.Default.DeleteSweep, null)
                    Text(" " + tx("Verlauf löschen"))
                }
            }
        }
        if (items.isEmpty()) { Empty(tx("Noch nichts angesehen.")); return@Column }
        LazyColumn {
            items(items) { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SimpleRow(item, "${tx(item.kind.label)} · ${item.group}") {
                            when (item.kind) {
                                Kind.LIVE -> {
                                    val live = items.filter { it.kind == Kind.LIVE }
                                    PlayQueue.playLive(ctx, live, live.indexOf(item))
                                }
                                Kind.MOVIE -> onMovie(item)
                                Kind.SERIES -> onSeries(item)
                            }
                        }
                    }
                    IconButton(onClick = { Repo.removeRecent(item) }, modifier = Modifier.tvFocus()) {
                        Icon(Icons.Default.Close, tx("Entfernen"), tint = Color.Gray)
                    }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tx("Verlauf wirklich löschen?")) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { Repo.clearHistory(false); confirm = false }) { Text(tx("Nur Liste löschen")) }
                    TextButton(onClick = { Repo.clearHistory(true); confirm = false }) { Text(tx("Alles löschen (auch Weiterschauen und Gesehen)")) }
                    TextButton(onClick = { confirm = false }) { Text(tx("Abbrechen")) }
                }
            },
        )
    }
}

@Composable
private fun MovieGrid(kind: Kind, items: List<Item>, emptyText: String, onOpen: (Item) -> Unit) {
    val extra = Repo.vodGroups[kind].orEmpty()
    if (items.isEmpty() && extra.isEmpty()) { Empty(emptyText); return }
    var group by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (Device.isTv) { delay(400); runCatching { first.requestFocus() } } }
    GroupBrowser(kind, items, extraGroups = extra, onSelect = { g -> group = g; Repo.vodNeedMore(kind, g) }) { list ->
        val state = rememberLazyGridState()
        // MAC-Portal: am Ende der Liste die nächste Seite nachladen
        val nearEnd by remember {
            derivedStateOf {
                val info = state.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 8
            }
        }
        LaunchedEffect(nearEnd, list.size, group) { if (nearEnd) Repo.vodNeedMore(kind, group) }
        if (list.isEmpty()) {
            if (Repo.vodBusy > 0) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else Empty(tx("Keine Einträge"))
        } else {
            // Ansicht: groß / mittel / Liste (Knopf oben)
            val cells = when (Repo.viewMode) {
                0 -> GridCells.Adaptive(165.dp)
                1 -> GridCells.Adaptive(115.dp)
                else -> GridCells.Fixed(1)
            }
            LazyVerticalGrid(cells, state = state, contentPadding = PaddingValues(8.dp)) {
                gridItemsIndexed(list) { i, it ->
                    val m = if (i == 0) Modifier.focusRequester(first) else Modifier
                    if (Repo.viewMode == 2) MediaRow(it, m) { onOpen(it) } else PosterCard(it, m) { onOpen(it) }
                }
            }
        }
    }
}

@Composable
private fun SeriesDetail(series: Item, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var eps by remember(series.id) { mutableStateOf<List<Episode>?>(null) }
    var err by remember(series.id) { mutableStateOf<String?>(null) }
    var season by remember(series.id) { mutableIntStateOf(-1) }
    LaunchedEffect(series.id) {
        try {
            eps = Repo.episodes(series)
        } catch (e: Exception) {
            err = e.message ?: tx("Fehler")
        }
    }
    val wv = Repo.watchVersion
    val fav = series.id in Repo.favs

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tx("Zurück")) }
            if (series.logo.isNotBlank()) {
                AsyncImage(series.logo, null, Modifier.width(70.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(series.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                val meta = listOf(series.year, series.rating.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" } ?: "").filter { it.isNotBlank() }
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = Color.Gray, fontSize = 12.sp)
                if (series.plot.isNotBlank()) Text(series.plot, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, color = Color(0xFFCCCCCC))
            }
            IconButton(onClick = { Repo.toggleFav(series) }) {
                Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, tx("Favorit"), tint = if (fav) Color(0xFFFFC107) else Color.Gray)
            }
        }
        val list = eps
        when {
            err != null -> Empty(tx("Fehler: %s", err))
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Empty(tx("Keine Folgen gefunden"))
            else -> {
                val seasons = list.map { it.season }.distinct().sorted()
                val cur = if (season in seasons) season else seasons.first()
                LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(seasons) { s -> FilterChip(selected = s == cur, onClick = { season = s }, label = { Text(tx("Staffel %s", s)) }) }
                }
                val seasonEps = list.filter { it.season == cur }
                LazyColumn {
                    itemsIndexed(seasonEps, key = { _, e -> e.id }) { i, e ->
                        val watched = remember(e.url, wv) { Repo.store.isWatched(e.url) }
                        val resume = remember(e.url, wv) { Repo.store.resumePos(e.url) }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).tvFocus().clip(RoundedCornerShape(8.dp)).clickable {
                                PlayQueue.start(
                                    ctx,
                                    seasonEps.map { PlayEntry("${series.name} · S${it.season} E${it.num} ${it.title}", it.url, false, series, it.image) },
                                    i,
                                )
                            }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                e.image.ifBlank { series.logo }, null,
                                Modifier.width(130.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentScale = ContentScale.Crop,
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text("${e.num}. ${e.title}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                val info = listOfNotNull(
                                    e.duration.ifBlank { null },
                                    if (watched) tx("✓ Gesehen") else null,
                                    if (resume > 0) tx("Weiter ab %s", fmtTime(resume)) else null,
                                )
                                if (info.isNotEmpty()) Text(info.joinToString(" · "), fontSize = 12.sp, color = if (watched) Color(0xFF43A047) else Color.Gray)
                                if (e.plot.isNotBlank()) Text(e.plot, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavTab(onMovie: (Item) -> Unit, onSeries: (Item) -> Unit, onProgram: (Item) -> Unit) {
    val ctx = LocalContext.current
    val c = Repo.content
    val favs = Repo.favs
    val all = remember(c, favs) { favs.mapNotNull { Repo.lookup(it) } }
    val live = all.filter { it.kind == Kind.LIVE }
    val movies = all.filter { it.kind == Kind.MOVIE }
    val series = all.filter { it.kind == Kind.SERIES }
    if (live.isEmpty() && movies.isEmpty() && series.isEmpty()) {
        Empty(tx("Noch keine Favoriten.\nTippe bei einem Kanal, Film oder einer Serie auf den Stern ☆."))
        return
    }
    LazyColumn(contentPadding = PaddingValues(8.dp)) {
        if (live.isNotEmpty()) {
            item { Header(tx("Live-Kanäle")) }
            itemsIndexed(live, key = { i, it -> "l$i${it.id}" }) { i, item ->
                ChannelRow(item, i + 1, onClick = { PlayQueue.playLive(ctx, live, i) }, onInfo = { onProgram(item) })
            }
        }
        if (movies.isNotEmpty()) {
            item { Header(tx("Filme")) }
            items(movies) { SimpleRow(it, it.group) { onMovie(it) } }
        }
        if (series.isNotEmpty()) {
            item { Header(tx("Serien")) }
            items(series) { SimpleRow(it, it.group) { onSeries(it) } }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SearchTab(onMovie: (Item) -> Unit, onSeries: (Item) -> Unit) {
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    val results = remember(q, Repo.content, Repo.unlocked) {
        if (q.trim().length < 2) emptyList()
        else Repo.content.all.filter { Repo.visible(it) && it.name.contains(q.trim(), ignoreCase = true) }.take(300)
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        OutlinedTextField(
            q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text(tx("Kanal, Film oder Serie suchen…")) },
        )
        if (q.trim().length >= 2 && results.isEmpty()) Empty(tx("Nichts gefunden"))
        LazyColumn {
            items(results) { item ->
                SimpleRow(item, "${tx(item.kind.label)} · ${item.group}") {
                    when (item.kind) {
                        Kind.LIVE -> {
                            val live = results.filter { it.kind == Kind.LIVE }
                            PlayQueue.playLive(ctx, live, live.indexOf(item))
                        }
                        Kind.MOVIE -> onMovie(item)
                        Kind.SERIES -> onSeries(item)
                    }
                }
            }
        }
    }
}

/** TV-Guide: Raster mit Kanälen (Zeilen) und Zeit (Spalten). */
@Composable
private fun GuideTab(onProgram: (Item) -> Unit) {
    if (Repo.epgStatus.isNotEmpty() && Epg.isEmpty) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(Repo.epgStatus, color = Color.Gray)
            Button(onClick = { Repo.reloadEpg() }, modifier = Modifier.padding(top = 12.dp)) { Text(tx("EPG neu laden")) }
        }
        return
    }
    GroupBrowser(Kind.LIVE, Repo.content.live) { list ->
        EpgGrid(list.take(150), onProgram)
    }
}

@Composable
private fun EpgGrid(channels: List<Item>, onProgram: (Item) -> Unit) {
    val ctx = LocalContext.current
    val v = Repo.epgVersion
    val now = remember { System.currentTimeMillis() }
    val start = remember { now - now % 1_800_000 - 3_600_000 }
    val hours = 24
    val end = start + hours * 3_600_000L
    val dpPerMin = 4f
    val rowH = 58.dp
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) { hScroll.scrollTo(with(density) { (60 * dpPerMin).dp.roundToPx() }) }
    val totalW = (hours * 60 * dpPerMin).dp

    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.width(140.dp)) {
            Spacer(Modifier.height(28.dp))
            channels.forEachIndexed { i, ch ->
                Row(
                    Modifier.height(rowH).fillMaxWidth().tvFocus().clickable { PlayQueue.playLive(ctx, channels, i) }.padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Logo(ch.logo, 32.dp)
                    Text(ch.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        Column(Modifier.horizontalScroll(hScroll)) {
            Box(Modifier.width(totalW).height(28.dp)) {
                for (h in 0 until hours * 2) {
                    val t = start + h * 1_800_000L
                    Text(hm(t), fontSize = 11.sp, color = Color.Gray, modifier = Modifier.offset(x = (h * 30 * dpPerMin).dp).padding(start = 4.dp, top = 6.dp))
                }
                Box(Modifier.offset(x = ((now - start) / 60_000f * dpPerMin).dp).width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
            channels.forEach { ch ->
                val progs = remember(ch.id, v) { Epg.programs(ch).filter { it.stop > start && it.start < end } }
                Box(Modifier.width(totalW).height(rowH)) {
                    if (progs.isEmpty()) {
                        Text(tx("Kein Programm"), fontSize = 11.sp, color = Color.DarkGray, modifier = Modifier.padding(8.dp))
                    }
                    progs.forEach { p ->
                        val s = maxOf(p.start, start)
                        val e = minOf(p.stop, end)
                        val live = p.start <= now && p.stop > now
                        Box(
                            Modifier.offset(x = ((s - start) / 60_000f * dpPerMin).dp)
                                .width(((e - s) / 60_000f * dpPerMin).dp.coerceAtLeast(4.dp))
                                .fillMaxHeight().padding(1.dp)
                                .tvFocus(RoundedCornerShape(4.dp))
                                .background(
                                    if (live) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(4.dp),
                                )
                                .clickable { onProgram(ch) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Column {
                                Text(p.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text("${hm(p.start)}–${hm(p.stop)}", fontSize = 10.sp, color = Color.Gray, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
