package com.tv26.player.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tv26.player.data.Kind
import com.tv26.player.data.L
import com.tv26.player.data.Net
import com.tv26.player.data.Repo
import com.tv26.player.data.Vpn
import com.tv26.player.data.tx
import com.tv26.player.player.RecordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val SubColors = listOf("Weiß" to "#FFFFFF", "Gelb" to "#FFEB3B", "Grün" to "#76FF03", "Cyan" to "#18FFFF")

@Composable
fun SettingsTab() {
    val ctx = LocalContext.current
    val store = Repo.store
    val scope = rememberCoroutineScope()
    var liveExt by remember { mutableStateOf(Repo.liveExt) }
    var pipAuto by remember { mutableStateOf(store.bool("pip_auto", true)) }
    var autoOpen by remember { mutableStateOf(store.bool("auto_open", true)) }
    var ua by remember { mutableStateOf(store.str("ua")) }
    var subSize by remember { mutableFloatStateOf(store.float("sub_size", 1f)) }
    var subColor by remember { mutableStateOf(store.str("sub_color", "#FFFFFF")) }
    var showPin by remember { mutableStateOf(false) }
    var showGroups by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf("") }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(store.exportJson().toByteArray()) }
        }.onSuccess { toast(ctx, tx("Backup gespeichert")) }.onFailure { toast(ctx, tx("Fehler: %s", it.message)) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val t = String(bytes).trimStart('\uFEFF', ' ', '\n', '\r')
            if (!t.startsWith("{")) {
                // Keine TV26-JSON-Datei, z. B. ein TiviMate-Backup (.tmb)
                throw IllegalArgumentException(tx("Dieses Backup-Format (z. B. TiviMate .tmb) wird noch nicht unterstützt. Bitte eine TV26-Backup-Datei (.json) wählen."))
            }
            val created = store.importJson(t)
            Repo.applySettings()
            val again = Repo.profiles.find { it.id == Repo.profile?.id }
            if (again != null) Repo.open(again) else Repo.close()
            created
        }.onSuccess { toast(ctx, if (it.isNotBlank()) tx("Backup vom %s geladen", it) else tx("Backup geladen")) }.onFailure { toast(ctx, tx("Fehler: %s", it.message)) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Section(tx("Sprache"))
        LangSwitch()

        Section(tx("MAC-ID"))
        Text(tx("Deine Geräte-MAC-ID: %s", remember { store.deviceMac() }), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(tx("Gib diese MAC-ID deinem Anbieter, damit er sie freischaltet."), fontSize = 13.sp, color = Color.Gray)

        Section(tx("VPN"))
        VpnSection()

        Section(tx("Sicheres DNS"))
        DnsSection()

        Section(tx("Wiedergabe"))
        Text(tx("Live-Format (nur Xtream)"), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ts" to "MPEG-TS (.ts)", "m3u8" to "HLS (.m3u8)").forEach { (k, l) ->
                FilterChip(selected = liveExt == k, onClick = {
                    liveExt = k
                    store.put("live_ext", k)
                    toast(ctx, tx("Gilt nach \"Neu laden\""))
                }, label = { Text(tx(l)) })
            }
        }
        SwitchRow(tx("Bild-im-Bild beim Verlassen der App"), pipAuto) { pipAuto = it; store.putBool("pip_auto", it) }
        SwitchRow(tx("Letzte Playlist beim Start öffnen"), autoOpen) { autoOpen = it; store.putBool("auto_open", it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                ua, { ua = it }, label = { Text(tx("User-Agent (leer = Standard)")) }, singleLine = true,
                placeholder = { Text(Net.DEFAULT_UA) }, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                store.put("ua", ua.trim())
                Net.userAgent = ua.trim().ifBlank { Net.DEFAULT_UA }
                toast(ctx, tx("Gespeichert"))
            }) { Text(tx("Speichern")) }
        }

        Section(tx("Untertitel"))
        Text(tx("Größe: %s %%", (subSize * 100).toInt()), fontSize = 14.sp)
        Slider(
            value = subSize, onValueChange = { subSize = it }, valueRange = 0.5f..2f, steps = 5,
            onValueChangeFinished = { store.put("sub_size", subSize.toString()) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubColors.forEach { (l, c) ->
                FilterChip(selected = subColor == c, onClick = { subColor = c; store.put("sub_color", c) }, label = { Text(tx(l)) })
            }
        }

        Section(tx("Design"))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Accents.forEachIndexed { i, (name, color) ->
                Box(
                    Modifier.size(40.dp).tvFocus(CircleShape).background(color, CircleShape)
                        .then(if (Repo.accent == i) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
                        .clickable { Repo.accent = i; store.put("accent", "$i") },
                )
            }
        }

        Section(tx("Kinderschutz"))
        Text(
            if (store.str("pin").isBlank()) tx("Keine PIN gesetzt.") else tx("PIN ist gesetzt. Gesperrte Gruppen brauchen die PIN."),
            fontSize = 13.sp, color = Color.Gray,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showPin = true }) { Icon(Icons.Default.Pin, null); Text(tx(" PIN festlegen")) }
            OutlinedButton(onClick = { showGroups = true }) { Icon(Icons.Default.Category, null); Text(tx(" Gruppen verwalten")) }
        }
        if (Repo.unlocked) TextButton(onClick = { Repo.unlocked = false }) { Text(tx("Gesperrte Gruppen wieder sperren")) }

        Section(tx("Werkzeuge"))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                speed = tx("Test läuft…")
                scope.launch { speed = speedTest() }
            }) { Icon(Icons.Default.Speed, null); Text(tx(" Internet-Speedtest")) }
            Text("  $speed")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = { Repo.reloadEpg(); toast(ctx, tx("EPG wird neu geladen")) }) { Icon(Icons.Default.Refresh, null); Text(tx(" EPG neu laden")) }
        }
        if (Repo.epgStatus.isNotEmpty()) Text(Repo.epgStatus, fontSize = 12.sp, color = Color.Gray)
        Text(
            tx("Das TV-Programm wird automatisch geladen und alle 6 Stunden erneuert. Mehrere EPG-Adressen mit Komma trennen."),
            fontSize = 12.sp, color = Color.Gray,
        )
        // Untereinander, damit die Texte auch auf schmalen Handys nicht umbrechen
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = {
                runCatching { saveBackupToDownloads(ctx) }
                    .onSuccess { toast(ctx, tx("Backup gespeichert: %s", it)) }
                    .onFailure { toast(ctx, tx("Fehler: %s", it.message)) }
            }) { Icon(Icons.Default.Save, null); Text(" " + tx("Backup in Download speichern")) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exporter.launch("tv26-backup-${com.tv26.player.data.Store.stamp()}.json") }) { Icon(Icons.Default.Upload, null); Text(tx(" Backup speichern")) }
                OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Icon(Icons.Default.Download, null); Text(tx(" Backup laden")) }
            }
        }
        Text(
            tx("Das Backup enthält Playlists, Favoriten, Verlauf und Einstellungen."),
            fontSize = 12.sp, color = Color.Gray,
        )

        Section(tx("Aufnahmen"))
        Text(tx("Aufnahmen werden im Ordner Download/TV26 als .ts-Datei gespeichert."), fontSize = 13.sp, color = Color.Gray)
        RecordService.active.forEach { (url, rec) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FiberManualRecord, null, tint = Color.Red)
                Text(" ${rec.title}", Modifier.weight(1f))
                TextButton(onClick = { RecordService.stop(ctx, url) }) { Text(tx("Stoppen")) }
            }
        }

        Section(tx("Cover für Filme und Serien (TMDB)"))
        TmdbSection()

        Section(tx("Über"))
        Text("TV26 - Player 1.0", fontWeight = FontWeight.Bold)
        Text(
            tx("Spielt deine eigenen M3U/M3U8-Playlists und Xtream-Codes-Zugänge ab. Die App enthält selbst keine Sender."),
            fontSize = 13.sp, color = Color.Gray,
        )
        // Pflicht-Hinweise der verwendeten Bibliotheken und Dienste
        Text(tx("Lizenzen"), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            "• WireGuard-Tunnel-Bibliothek (Apache License 2.0). \"WireGuard\" ist eine eingetragene Marke von Jason A. Donenfeld.\n" +
                "• OkHttp (Apache License 2.0) · Coil (Apache License 2.0) · AndroidX Media3 (Apache License 2.0)\n" +
                "• Sender-Logos: iptv-org (frei nutzbar)\n" +
                "• This product uses the TMDB API but is not endorsed or certified by TMDB.",
            fontSize = 12.sp, color = Color.Gray,
        )
        Spacer(Modifier.height(40.dp))
    }

    if (showPin) SetPinDialog { showPin = false }
    if (showGroups) ManageDialog { showGroups = false }
}

/** Backup mit einem Tipp in Download/TV26 speichern. Gibt den Ort zurück. */
private fun saveBackupToDownloads(ctx: android.content.Context): String {
    val name = "tv26-backup-${com.tv26.player.data.Store.stamp()}.json"
    val data = Repo.store.exportJson().toByteArray()
    if (android.os.Build.VERSION.SDK_INT >= 29) {
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/TV26")
        }
        val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw java.io.IOException(tx("Speicher nicht verfügbar"))
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
        return "Download/TV26/$name"
    }
    val dir = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "TV26").apply { mkdirs() }
    val f = java.io.File(dir, name)
    f.writeBytes(data)
    return f.absolutePath
}

/** Sprache wechseln: Deutsch oder Türkisch. */
@Composable
fun LangSwitch() {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("de" to "Deutsch", "tr" to "Türkçe").forEach { (code, name) ->
            FilterChip(
                selected = L.lang == code,
                onClick = { L.set(Repo.store, code) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))
    Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().tvFocus().clickable { onChange(!value) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

private suspend fun speedTest(): String = withContext(Dispatchers.IO) {
    try {
        val t0 = System.currentTimeMillis()
        var bytes = 0L
        Net.open("https://speed.cloudflare.com/__down?bytes=50000000").use { s ->
            val buf = ByteArray(64 * 1024)
            while (System.currentTimeMillis() - t0 < 8000) {
                val n = s.read(buf)
                if (n < 0) break
                bytes += n
            }
        }
        val sec = (System.currentTimeMillis() - t0) / 1000.0
        String.format(Locale.GERMANY, "%.1f Mbit/s", bytes * 8 / sec / 1_000_000)
    } catch (e: Exception) {
        tx("Fehler: %s", e.message)
    }
}

@Composable
fun SetPinDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val old = Repo.store.str("pin")
    var oldIn by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx("PIN festlegen")) },
        text = {
            Column {
                if (old.isNotBlank()) {
                    OutlinedTextField(
                        oldIn, { oldIn = it.filter(Char::isDigit).take(8) }, label = { Text(tx("Alte PIN")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    )
                }
                OutlinedTextField(
                    newPin, { newPin = it.filter(Char::isDigit).take(8) }, label = { Text(tx("Neue PIN (leer = keine PIN)")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (old.isNotBlank() && oldIn != old) {
                    toast(ctx, tx("Alte PIN ist falsch"))
                } else {
                    Repo.store.put("pin", newPin)
                    if (newPin.isBlank()) Repo.unlocked = true
                    toast(ctx, tx("PIN gespeichert"))
                    onDismiss()
                }
            }) { Text(tx("Speichern")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx("Abbrechen")) } },
    )
}

/** VPN: Konfigurationen verwalten, verbinden/trennen, automatisch starten, Schutz. */
@Composable
private fun VpnSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confs by remember { mutableStateOf(Vpn.list()) }
    var sel by remember { mutableStateOf(Vpn.selected) }
    var auto by remember { mutableStateOf(Vpn.autoStart) }
    var req by remember { mutableStateOf(Vpn.required) }
    var pasting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (Vpn.publicIp.isBlank()) Vpn.checkIp() }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) scope.launch { Vpn.connect(sel) }
        else toast(ctx, tx("VPN wurde nicht erlaubt"))
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val name = (uri.lastPathSegment ?: "VPN").substringAfterLast('/').substringBeforeLast('.')
            Vpn.add(name, text)
        }.onSuccess { confs = Vpn.list(); sel = Vpn.selected; toast(ctx, tx("VPN-Konfiguration gespeichert")) }
            .onFailure { toast(ctx, tx("Ungültige VPN-Konfiguration: %s", it.message)) }
    }
    fun connect() {
        val ask = Vpn.permissionIntent(ctx)
        if (ask != null) permission.launch(ask) else scope.launch { Vpn.connect(sel) }
    }

    // Status
    val (statusText, statusColor) = when (Vpn.state) {
        Vpn.State.ON -> tx("Verbunden") to Color(0xFF43A047)
        Vpn.State.CONNECTING -> tx("Verbinde…") to Color(0xFFFB8C00)
        Vpn.State.ERROR -> tx("Fehler: %s", Vpn.message) to MaterialTheme.colorScheme.error
        Vpn.State.OFF -> tx("Aus") to Color.Gray
    }
    Text(
        tx("VPN ist optional. Ist eine Konfiguration da, verbindet die App beim Start automatisch. Ohne VPN schützt die App automatisch mit sicherem DNS."),
        fontSize = 12.sp, color = Color.Gray,
    )
    Text(statusText, color = statusColor, fontWeight = FontWeight.Bold)
    if (Vpn.publicIp.isNotBlank()) Text(tx("Sichtbare IP: %s", Vpn.publicIp), fontSize = 13.sp)

    if (confs.isEmpty()) {
        Text(
            tx("Noch keine VPN-Konfiguration. Lade die WireGuard-Konfiguration (.conf) deines VPN-Anbieters, z. B. für Deutschland oder die Türkei."),
            fontSize = 13.sp, color = Color.Gray,
        )
    }
    confs.forEach { c ->
        Row(Modifier.fillMaxWidth().tvFocus().clickable { sel = c.name; Vpn.selected = c.name }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = sel == c.name, onClick = { sel = c.name; Vpn.selected = c.name })
            Text(c.name, Modifier.weight(1f))
            IconButton(onClick = { Vpn.remove(c.name); confs = Vpn.list(); sel = Vpn.selected }) { Icon(Icons.Default.Delete, tx("Löschen")) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FolderOpen, null); Text(" " + tx("Datei (.conf)")) }
        OutlinedButton(onClick = { pasting = true }) { Icon(Icons.Default.ContentPaste, null); Text(" " + tx("Text einfügen")) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
        if (Vpn.state == Vpn.State.ON) {
            Button(onClick = { scope.launch { Vpn.disconnect() } }) { Icon(Icons.Default.VpnLock, null); Text(" " + tx("Trennen")) }
        } else {
            Button(onClick = { connect() }, enabled = sel.isNotBlank() && Vpn.state != Vpn.State.CONNECTING) {
                Icon(Icons.Default.VpnKey, null); Text(" " + tx("Verbinden"))
            }
        }
        OutlinedButton(onClick = { scope.launch { Vpn.checkIp() } }) { Text(tx("IP prüfen")) }
    }
    SwitchRow(tx("VPN beim Start automatisch verbinden"), auto) { auto = it; Vpn.autoStart = it }
    SwitchRow(tx("Nur mit VPN abspielen (Schutz)"), req) { req = it; Vpn.required = it }
    Text(tx("Das VPN gilt nur für TV26, andere Apps bleiben normal."), fontSize = 12.sp, color = Color.Gray)

    if (pasting) {
        var name by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text(tx("VPN-Konfiguration einfügen")) },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text(tx("Name (z. B. DE oder TR)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, label = { Text("[Interface] …") }, minLines = 6, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && text.isNotBlank(), onClick = {
                    runCatching { Vpn.add(name.trim(), text) }
                        .onSuccess { confs = Vpn.list(); sel = Vpn.selected; pasting = false; toast(ctx, tx("VPN-Konfiguration gespeichert")) }
                        .onFailure { toast(ctx, tx("Ungültige VPN-Konfiguration: %s", it.message)) }
                }) { Text(tx("Speichern")) }
            },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text(tx("Abbrechen")) } },
        )
    }
}

/** Sicheres DNS (DNS über HTTPS): hilft, wenn der Internet-Anbieter IPTV-Server per DNS sperrt. */
@Composable
private fun DnsSection() {
    var mode by remember { mutableStateOf(Repo.store.str("dns_mode", "auto")) }
    val labels = mapOf("auto" to tx("Automatisch (empfohlen)"), "system" to tx("System"), "cloudflare" to "Cloudflare", "google" to "Google", "quad9" to "Quad9", "adguard" to "AdGuard")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Net.DNS_PROVIDERS.keys.forEach { k ->
            FilterChip(selected = mode == k, onClick = {
                mode = k
                Repo.store.put("dns_mode", k)
                Net.setDns(k)
            }, label = { Text(labels[k] ?: k) })
        }
    }
    Text(
        tx("Sicheres DNS hilft gegen DNS-Sperren deines Internet-Anbieters. Es versteckt deine IP nicht – dafür ist das VPN da. Gilt nach \"Neu laden\"."),
        fontSize = 12.sp, color = Color.Gray,
    )
}

/** Optionaler TMDB-Schlüssel für Film- und Serien-Cover. */
@Composable
private fun TmdbSection() {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(Repo.store.str("tmdb_key")) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(key, { key = it.trim() }, label = { Text(tx("TMDB API-Schlüssel (optional)")) }, singleLine = true, modifier = Modifier.weight(1f))
        TextButton(onClick = { Repo.store.put("tmdb_key", key); toast(ctx, tx("Gespeichert")) }) { Text(tx("Speichern")) }
    }
    Text(
        tx("Kostenlos auf themoviedb.org → Konto → Einstellungen → API. Ohne Schlüssel nutzt die App nur die Bilder aus deiner Quelle."),
        fontSize = 12.sp, color = Color.Gray,
    )
}
