package com.tv26.player.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
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
import com.tv26.player.data.Profile
import com.tv26.player.data.Repo
import com.tv26.player.data.SourceType
import com.tv26.player.data.Stalker
import com.tv26.player.data.Vpn
import com.tv26.player.data.Device
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.tv26.player.data.cleanUrl
import com.tv26.player.data.findMac
import com.tv26.player.data.tx
import java.util.UUID

class MainActivity : ComponentActivity() {
    /** Antwort auf die Android-Frage "Darf TV26 ein VPN aufbauen?" */
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        lifecycleScope.launch {
            if (r.resultCode == RESULT_OK) Vpn.connect()
            openLastPlaylist()
        }
    }

    /** Chromebook/Tastatur: Esc wirkt wie "Zurück" */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == android.view.KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openLastPlaylist() {
        if (Repo.profile != null || !Repo.store.bool("auto_open", true)) return
        val last = Repo.store.str("last_profile")
        Repo.profiles.find { it.id == last }?.let { Repo.open(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareLink(intent)
    }

    /** Wurde die App über einen geteilten Link (tv26://s/<code>) geöffnet? */
    private fun handleShareLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme.equals("tv26", true)) {
            ReceiveState.pending = data.toString()
            ReceiveState.version++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareLink(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null && Repo.profile == null) {
            if (Vpn.autoStart && Vpn.selected.isNotBlank() && Vpn.state != Vpn.State.ON) {
                // VPN zuerst verbinden, DANN die Playlist laden – so geht schon die erste Anfrage über das VPN
                val ask = Vpn.permissionIntent(this)
                if (ask != null) vpnPermission.launch(ask)
                else lifecycleScope.launch { Vpn.connect(); openLastPlaylist() }
            } else {
                openLastPlaylist()
            }
        }
        setContent {
            TV26Theme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // Fernseher: Sicherheitsrand, damit am Bildrand nichts abgeschnitten wird (Overscan)
                    Box(if (Device.isTv) Modifier.padding(horizontal = 40.dp, vertical = 20.dp) else Modifier.safeDrawingPadding()) {
                        if (Repo.profile == null) ProfilesScreen() else HomeScreen()
                    }
                }
            }
        }
    }
}

/** Merkt sich einen geteilten Link, bis der Empfangen-Dialog ihn verarbeitet. */
object ReceiveState {
    var pending by mutableStateOf("")
    var version by mutableIntStateOf(0)
}

@Composable
fun ProfilesScreen() {
    var editing by remember { mutableStateOf<Profile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Profile?>(null) }
    var sharing by remember { mutableStateOf<Profile?>(null) }
    var receiving by remember { mutableStateOf(ReceiveState.pending.isNotEmpty()) }
    // Kam die App über einen geteilten Link herein, gleich den Empfangen-Dialog zeigen
    LaunchedEffect(ReceiveState.version) { if (ReceiveState.pending.isNotEmpty()) receiving = true }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LiveTv, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Text(" TV26 - Player", fontSize = 26.sp, fontWeight = FontWeight.Black)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LangSwitch()
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { receiving = true }, modifier = Modifier.tvFocus()) {
                Icon(Icons.Default.QrCodeScanner, null)
                Text(" " + tx("Empfangen"))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { creating = true }, modifier = Modifier.tvFocus()) {
                Icon(Icons.Default.Add, null)
                Text(tx("Playlist hinzufügen"))
            }
        }
        Spacer(Modifier.height(16.dp))
        if (Repo.profiles.isEmpty()) {
            Text(
                tx("Noch keine Playlist.\n\nFüge einen M3U/M3U8-Link, eine M3U-Datei oder einen Xtream-Codes-Zugang hinzu."),
                color = Color.Gray,
            )
        }
        LazyColumn {
            items(Repo.profiles) { p ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).tvFocus().clickable { Repo.open(p) },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(
                                tx(p.type.label) + (if (p.type == SourceType.M3U_FILE) "" else " · " + p.url.substringAfter("://").substringBefore('/')) +
                                    (if (p.type == SourceType.STALKER) " · MAC ${p.user}" else ""),
                                fontSize = 12.sp, color = Color.Gray,
                            )
                        }
                        IconButton(onClick = { sharing = p }) { Icon(Icons.Default.Share, tx("Teilen")) }
                        IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, tx("Bearbeiten")) }
                        IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, tx("Löschen")) }
                    }
                }
            }
        }
    }

    if (creating || editing != null) ProfileDialog(editing) { creating = false; editing = null }
    sharing?.let { ShareDialog(it) { sharing = null } }
    if (receiving) {
        val link = ReceiveState.pending
        ReceiveDialog(initialCode = link) { receiving = false; ReceiveState.pending = "" }
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tx("Playlist löschen?")) },
            text = { Text(tx("\"%s\" wird gelöscht.", p.name)) },
            confirmButton = { TextButton(onClick = { Repo.deleteProfile(p); deleting = null }) { Text(tx("Löschen")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tx("Abbrechen")) } },
        )
    }
}

@Composable
fun ProfileDialog(p: Profile?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var type by remember { mutableStateOf(p?.type ?: SourceType.M3U_URL) }
    var name by remember { mutableStateOf(p?.name ?: "") }
    var url by remember { mutableStateOf(p?.url ?: "") }
    var user by remember { mutableStateOf(p?.user ?: "") }
    var pass by remember { mutableStateOf(p?.pass ?: "") }
    var epg by remember { mutableStateOf(p?.epgUrl ?: "") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            url = uri.toString()
            if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: tx("Datei")
        }
    }
    val deviceMac = remember { Repo.store.deviceMac() }
    // MAC-Portal: nur ein leeres Feld mit der MAC-ID dieses Geräts vorausfüllen
    LaunchedEffect(type) {
        if (type == SourceType.STALKER && user.isBlank()) user = deviceMac
    }
    val valid = url.isNotBlank() && (type != SourceType.XTREAM || user.isNotBlank()) &&
        (type != SourceType.M3U_FILE || url.startsWith("content:")) &&
        (type != SourceType.STALKER || Stalker.validMac(user))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p == null) tx("Playlist hinzufügen") else tx("Playlist bearbeiten")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceType.entries.forEach { st ->
                        FilterChip(selected = type == st, onClick = { type = st }, label = { Text(tx(st.label)) })
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(tx("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                when (type) {
                    SourceType.M3U_URL -> OutlinedTextField(
                        url, { url = it }, label = { Text(tx("M3U / M3U8 URL")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                    )
                    SourceType.M3U_FILE -> Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Default.FolderOpen, null)
                        Text(if (url.startsWith("content:")) tx(" Datei gewählt ✓") else tx(" Datei wählen"))
                    }
                    SourceType.XTREAM -> {
                        OutlinedTextField(
                            url, { url = it }, label = { Text(tx("Server (z. B. http://server.com:8080)")) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(user, { user = it }, label = { Text(tx("Benutzername")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            pass, { pass = it }, label = { Text(tx("Passwort")) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SourceType.STALKER -> {
                        OutlinedTextField(
                            url,
                            { v ->
                                // Eingefügter Text mit Adresse UND MAC: aufteilen. Eine schon eingetragene MAC bleibt.
                                findMac(v)?.let { m -> if (user.isBlank() || user == deviceMac) user = m }
                                url = if (v.contains('\n') || findMac(v) != null) cleanUrl(v) else v
                            },
                            label = { Text(tx("Portal-Adresse (z. B. http://portal.com:8080/c/)")) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            user,
                            { v -> user = findMac(v) ?: v.uppercase().replace('-', ':').filter { it.isLetterOrDigit() || it == ':' }.take(17) },
                            label = { Text(tx("MAC-Adresse")) }, singleLine = true,
                            isError = user.isNotBlank() && !Stalker.validMac(user),
                            supportingText = if (user.isNotBlank() && !Stalker.validMac(user)) {
                                { Text(tx("Ungültige MAC-Adresse (Format: 00:1A:79:12:34:56)")) }
                            } else null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(tx("Deine Geräte-MAC-ID: %s", deviceMac), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        Text(tx("Gib diese MAC-ID deinem Anbieter, damit er sie freischaltet."), fontSize = 12.sp, color = Color.Gray)
                        if (user != deviceMac) {
                            TextButton(onClick = { user = deviceMac }) { Text(tx("Geräte-MAC-ID einsetzen")) }
                        }
                    }
                }
                OutlinedTextField(
                    epg, { epg = it }, label = { Text(tx("EPG-URL (optional, XMLTV)")) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    tx("Ohne EPG-URL nimmt die App das TV-Programm aus der Playlist (url-tvg) oder vom Xtream-Server.") + " " +
                        tx("Das TV-Programm wird automatisch geladen und alle 6 Stunden erneuert. Mehrere EPG-Adressen mit Komma trennen."),
                    fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val np = Profile(
                    p?.id ?: UUID.randomUUID().toString(), name.trim().ifBlank { tx(type.label) }, type,
                    if (type == SourceType.M3U_FILE) url.trim() else cleanUrl(url),
                    if (type == SourceType.STALKER) findMac(user) ?: user.trim() else user.trim(),
                    pass.trim(), epg.trim(),
                )
                Repo.saveProfile(np)
                onDismiss()
            }) { Text(tx("Speichern")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx("Abbrechen")) } },
    )
}
