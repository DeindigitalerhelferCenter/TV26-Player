package com.tv26.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tv26.player.data.Item
import com.tv26.player.data.Kind
import com.tv26.player.data.Repo
import com.tv26.player.data.tx

private val prefixRe = Regex("""^[\s|\[(]*([A-Za-z]{2,4})\s*[|:\-\])]""")
private val adultRe = Regex("""(?i)(xxx|18\s*\+|\+\s*18|adult|porn|erotik|erotic|yeti[sş]kin|for adults|\bsex)""")

/** Länder-/Sprach-Kürzel am Anfang eines Gruppennamens: "ENG | Sport" -> "ENG" */
fun groupPrefix(g: String): String? = prefixRe.find(g)?.groupValues?.get(1)?.uppercase()

private fun kindLabel(k: Kind) = when (k) {
    Kind.LIVE -> tx("Live-TV")
    Kind.MOVIE -> tx("Filme")
    Kind.SERIES -> tx("Serien")
}

/**
 * Gruppen verwalten – einfach:
 * Jede Zeile hat 2 Kästchen: "Anzeigen" und "18+ (PIN)". Die Kästchen zeigen den gespeicherten Stand.
 * Änderungen gelten erst nach "OK – Speichern". "Abbrechen" verwirft sie.
 */
@Composable
fun ManageDialog(onDismiss: () -> Unit) {
    // Entwurf = Kopie des gespeicherten Stands. Erst "OK" schreibt ihn zurück.
    var hiddenDraft by remember { mutableStateOf(Repo.hidden) }
    var lockedDraft by remember { mutableStateOf(Repo.locked) }
    var hiddenItemsDraft by remember { mutableStateOf(Repo.hiddenItems) }
    var lockedItemsDraft by remember { mutableStateOf(Repo.lockedItems) }
    var kind by remember { mutableStateOf(Kind.LIVE) }
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) } // 0 = alle, 1 = ausgeblendet, 2 = gesperrt
    var showItems by remember { mutableStateOf(false) }
    var askSave by remember { mutableStateOf(false) }
    var askPin by remember { mutableStateOf(false) }

    val changes = (hiddenDraft xor Repo.hidden) + (lockedDraft xor Repo.locked) +
        (hiddenItemsDraft xor Repo.hiddenItems) + (lockedItemsDraft xor Repo.lockedItems)

    fun save() {
        val needsPin = (lockedDraft.isNotEmpty() || lockedItemsDraft.isNotEmpty()) && Repo.store.str("pin").isBlank()
        if (needsPin) { askPin = true; return }
        Repo.saveGroupFlags(hiddenDraft, lockedDraft)
        Repo.saveItemFlags(hiddenItemsDraft, lockedItemsDraft)
        onDismiss()
    }
    fun close() { if (changes > 0) askSave = true else onDismiss() }

    val c = Repo.content
    val groupsAll = remember(c, kind, Repo.vodGroups) {
        val items = when (kind) {
            Kind.LIVE -> c.live
            Kind.MOVIE -> c.movies
            Kind.SERIES -> c.series
        }
        val counts = items.groupingBy { it.group }.eachCount()
        (Repo.vodGroups[kind].orEmpty() + counts.keys).distinct().map { it to (counts[it] ?: 0) }
    }
    fun key(g: String) = Repo.groupKey(kind, g)
    val groups = groupsAll.filter { (g, _) ->
        (q.isBlank() || g.contains(q.trim(), ignoreCase = true)) && when (filter) {
            1 -> key(g) in hiddenDraft
            2 -> key(g) in lockedDraft
            else -> true
        }
    }
    val prefixes = remember(groupsAll) {
        groupsAll.mapNotNull { groupPrefix(it.first) }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(40)
    }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                // Kopf
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (showItems) showItems = false else close() }, modifier = Modifier.tvFocus()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, tx("Zurück"))
                    }
                    Text(
                        if (showItems) tx("Einzelne Einträge") else tx("Gruppen verwalten"),
                        fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f),
                    )
                }

                if (showItems) {
                    ItemsTable(
                        hiddenItemsDraft, lockedItemsDraft,
                        onHidden = { id, show -> hiddenItemsDraft = if (show) hiddenItemsDraft - id else hiddenItemsDraft + id },
                        onLocked = { id, lock -> lockedItemsDraft = if (lock) lockedItemsDraft + id else lockedItemsDraft - id },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Art: Live / Filme / Serien + Link zu einzelnen Einträgen
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Kind.entries.forEach { k ->
                            FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(kindLabel(k)) })
                        }
                        val n = (hiddenItemsDraft + lockedItemsDraft).size
                        AssistChip(onClick = { showItems = true }, label = { Text(tx("Einzelne Einträge") + " ($n)") })
                    }
                    OutlinedTextField(
                        q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text(tx("Gruppe suchen…")) },
                    )
                    // Filter
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        listOf(tx("Alle"), tx("Nur ausgeblendete"), tx("Nur gesperrte")).forEachIndexed { i, l ->
                            FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(l) })
                        }
                    }
                    // Schnell-Knöpfe: setzen nur die Kästchen, gespeichert wird mit OK
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        item {
                            AssistChip(onClick = { hiddenDraft = hiddenDraft - groups.map { key(it.first) }.toSet() },
                                label = { Text(tx("Alle anzeigen")) }, leadingIcon = { Icon(Icons.Default.Visibility, null, Modifier.size(16.dp)) })
                        }
                        item {
                            AssistChip(onClick = { hiddenDraft = hiddenDraft + groups.map { key(it.first) } },
                                label = { Text(tx("Alle ausblenden")) }, leadingIcon = { Icon(Icons.Default.VisibilityOff, null, Modifier.size(16.dp)) })
                        }
                        item {
                            AssistChip(onClick = { lockedDraft = lockedDraft + groupsAll.filter { adultRe.containsMatchIn(it.first) }.map { key(it.first) } },
                                label = { Text(tx("18+ finden und sperren")) }, leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) })
                        }
                        // Länder: ein Tipp blendet alle Gruppen dieses Landes aus, noch ein Tipp wieder ein
                        items(prefixes) { (p, n) ->
                            val keys = groupsAll.filter { groupPrefix(it.first) == p }.map { key(it.first) }
                            val allHidden = keys.all { it in hiddenDraft }
                            FilterChip(
                                selected = allHidden,
                                onClick = { hiddenDraft = if (allHidden) hiddenDraft - keys.toSet() else hiddenDraft + keys },
                                label = { Text("$p ($n)") },
                                leadingIcon = if (allHidden) { { Icon(Icons.Default.VisibilityOff, null, Modifier.size(16.dp)) } } else null,
                            )
                        }
                    }
                    // Spaltenköpfe
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tx("Gruppe"), Modifier.weight(1f), fontSize = 12.sp, color = Color.Gray)
                        Text(tx("Anzeigen"), Modifier.width(80.dp), fontSize = 12.sp, color = Color.Gray)
                        Text(tx("18+ (PIN)"), Modifier.width(80.dp), fontSize = 12.sp, color = Color.Gray)
                    }
                    HorizontalDivider()
                    if (groups.isEmpty()) {
                        Box(Modifier.weight(1f)) { Empty(tx("Keine Einträge")) }
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(groups) { (g, n) ->
                                val k = key(g)
                                FlagRow(
                                    title = g, subtitle = if (n > 0) "$n" else "",
                                    visible = k !in hiddenDraft, locked = k in lockedDraft,
                                    onVisible = { show -> hiddenDraft = if (show) hiddenDraft - k else hiddenDraft + k },
                                    onLocked = { lock -> lockedDraft = if (lock) lockedDraft + k else lockedDraft - k },
                                )
                            }
                        }
                    }
                }

                // Fuß: Abbrechen / OK
                HorizontalDivider()
                Text(
                    if (changes > 0) tx("%s Änderungen", changes) else tx("Keine Änderungen"),
                    Modifier.padding(top = 6.dp), color = if (changes > 0) MaterialTheme.colorScheme.primary else Color.Gray,
                )
                Row(
                    // Abstand nach unten, damit der Navigations-Balken die Knöpfe nicht verdeckt
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 6.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.tvFocus()) { Text(tx("Abbrechen")) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { save() }, enabled = changes > 0, modifier = Modifier.tvFocus()) {
                        Icon(Icons.Default.Check, null)
                        Text(" " + tx("OK – Speichern"))
                    }
                }
            }
        }
    }

    if (askSave) {
        AlertDialog(
            onDismissRequest = { askSave = false },
            title = { Text(tx("Änderungen speichern?")) },
            text = { Text(tx("%s Änderungen", changes)) },
            confirmButton = { TextButton(onClick = { askSave = false; save() }) { Text(tx("OK – Speichern")) } },
            dismissButton = { TextButton(onClick = { askSave = false; onDismiss() }) { Text(tx("Verwerfen")) } },
        )
    }
    // Sperren braucht eine PIN: zuerst festlegen, dann speichern
    if (askPin) {
        SetPinDialog {
            askPin = false
            if (Repo.store.str("pin").isNotBlank()) save()
        }
    }
}

private infix fun Set<String>.xor(other: Set<String>) = (this - other).size + (other - this).size

/** Eine Zeile mit Name und den 2 Kästchen "Anzeigen" und "18+ (PIN)". */
@Composable
private fun FlagRow(
    title: String, subtitle: String, visible: Boolean, locked: Boolean,
    onVisible: (Boolean) -> Unit, onLocked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (visible) Color.Unspecified else Color.Gray)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
        }
        Box(Modifier.width(80.dp), contentAlignment = Alignment.CenterStart) {
            Checkbox(checked = visible, onCheckedChange = onVisible, modifier = Modifier.tvFocus())
        }
        Box(Modifier.width(80.dp), contentAlignment = Alignment.CenterStart) {
            Checkbox(checked = locked, onCheckedChange = onLocked, modifier = Modifier.tvFocus())
        }
    }
}

/** Einzeln ausgeblendete/gesperrte Kanäle, Filme, Serien – gleiche Kästchen wie bei den Gruppen. */
@Composable
private fun ItemsTable(
    hiddenIds: Set<String>, lockedIds: Set<String>,
    onHidden: (String, Boolean) -> Unit, onLocked: (String, Boolean) -> Unit,
    modifier: Modifier,
) {
    // Alle, die beim Öffnen markiert waren, bleiben in der Liste – auch wenn man die Kästchen ändert
    val ids = remember { (Repo.hiddenItems + Repo.lockedItems).toList() }
    val items = ids.mapNotNull { Repo.lookup(it) }
    Column(modifier) {
        Text(
            tx("Einzelne Kanäle, Filme und Serien markierst du mit langem Drücken in der Liste."),
            fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(tx("Eintrag"), Modifier.weight(1f), fontSize = 12.sp, color = Color.Gray)
            Text(tx("Anzeigen"), Modifier.width(80.dp), fontSize = 12.sp, color = Color.Gray)
            Text(tx("18+ (PIN)"), Modifier.width(80.dp), fontSize = 12.sp, color = Color.Gray)
        }
        HorizontalDivider()
        if (items.isEmpty()) { Empty(tx("Keine Einträge")); return@Column }
        LazyColumn {
            items(items) { item ->
                FlagRow(
                    title = item.name, subtitle = "${tx(item.kind.label)} · ${item.group}",
                    visible = item.id !in hiddenIds, locked = item.id in lockedIds,
                    onVisible = { onHidden(item.id, it) }, onLocked = { onLocked(item.id, it) },
                )
            }
        }
    }
}

/** Menü für einen einzelnen Kanal, Film oder eine Serie (lange drücken). */
@Composable
fun ItemMenu(item: Item, onDismiss: () -> Unit) {
    var pinThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun withPin(action: () -> Unit) {
        if (Repo.store.str("pin").isBlank()) pinThen = action else action()
    }
    val fav = item.id in Repo.favs
    val hidden = item.id in Repo.hiddenItems
    val locked = item.id in Repo.lockedItems
    val gk = Repo.groupKey(item.kind, item.group)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 2) },
        text = {
            Column {
                Text("${tx(item.kind.label)} · ${item.group}", fontSize = 13.sp, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                MenuRow(if (fav) Icons.Default.Star else Icons.Default.StarBorder, if (fav) tx("Aus Favoriten entfernen") else tx("Zu Favoriten hinzufügen")) {
                    Repo.toggleFav(item); onDismiss()
                }
                MenuRow(if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, if (hidden) tx("Wieder einblenden") else tx("Ausblenden (aus Listen entfernen)")) {
                    Repo.toggleItemHidden(item); onDismiss()
                }
                MenuRow(if (locked) Icons.Default.LockOpen else Icons.Default.Lock, if (locked) tx("18+ Sperre entfernen") else tx("18+ sperren (PIN)")) {
                    if (locked) { Repo.toggleItemLocked(item); onDismiss() } else withPin { Repo.toggleItemLocked(item); onDismiss() }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                MenuRow(Icons.Default.VisibilityOff, tx("Ganze Gruppe ausblenden")) {
                    Repo.setGroupsHidden(listOf(gk), true); onDismiss()
                }
                MenuRow(Icons.Default.Lock, if (gk in Repo.locked) tx("Gruppen-Sperre entfernen") else tx("Ganze Gruppe 18+ sperren")) {
                    if (gk in Repo.locked) { Repo.setGroupsLocked(listOf(gk), false); onDismiss() }
                    else withPin { Repo.setGroupsLocked(listOf(gk), true); onDismiss() }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tx("Schließen")) } },
    )
    pinThen?.let { action ->
        SetPinDialog {
            pinThen = null
            if (Repo.store.str("pin").isNotBlank()) action()
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tvFocus().clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null)
        Text("  $label")
    }
}
