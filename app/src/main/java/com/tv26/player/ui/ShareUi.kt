@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.tv26.player.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tv26.player.data.Profile
import com.tv26.player.data.Repo
import com.tv26.player.data.Share
import com.tv26.player.data.SourceType
import com.tv26.player.data.tx

/** Höchstlänge, bis zu der ein QR-Code noch gut scannbar bleibt. */
private const val QR_MAX = 1400

private fun qrBitmap(text: String, size: Int = 640): Bitmap? = try {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        for (x in 0 until size) for (y in 0 until size) {
            setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
} catch (e: Exception) {
    null
}

/** Playlist teilen: QR-Code + Text-Code, optional mit Passwort, plus "Senden" (WhatsApp usw.). */
@Composable
fun ShareDialog(profile: Profile, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var password by remember { mutableStateOf("") }
    // Bei M3U-Datei den Datei-Inhalt mitpacken (der Freund hat die Datei nicht)
    val fileContent = remember(profile.id) {
        if (profile.type == SourceType.M3U_FILE) {
            runCatching { ctx.contentResolver.openInputStream(android.net.Uri.parse(profile.url))!!.bufferedReader().use { it.readText() } }.getOrNull()
        } else null
    }
    val code = remember(password, fileContent) { Share.encode(profile, fileContent, password) }
    val qr = remember(code) { if (code.length <= QR_MAX) qrBitmap(Share.asLink(code)) else null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tx("Playlist teilen")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(profile.name, fontWeight = FontWeight.Bold)
                Text(
                    tx("Der Freund öffnet \"Playlist empfangen\" und scannt den QR-Code oder gibt den Code ein."),
                    fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp),
                )
                OutlinedTextField(
                    password, { password = it }, label = { Text(tx("Passwort (optional)")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (qr != null) {
                    Image(
                        qr.asImageBitmap(), null,
                        Modifier.size(220.dp).background(Color.White).padding(8.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(tx("Zu groß für einen QR-Code. Bitte den Code unten senden."), fontSize = 12.sp, color = Color.Gray)
                }
                Spacer(Modifier.height(8.dp))
                // Code zum Abschreiben/Kopieren
                SelectionContainer {
                    Text(code, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFBBBBBB), maxLines = 4)
                }
                if (password.isNotBlank()) {
                    Text(tx("Sag dem Freund das Passwort getrennt (nicht im selben Chat)."), fontSize = 11.sp, color = Color(0xFFFFC107), modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, tx("Öffne das in TV26: %s", Share.asLink(code)))
                ctx.startActivity(Intent.createChooser(send, tx("Senden")))
            }) { Icon(Icons.Default.Share, null); Text(" " + tx("Senden")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tx("Schließen")) } },
    )
}

/** Playlist empfangen: Code einfügen (oder Link), optional Passwort, speichern. */
@Composable
fun ReceiveDialog(initialCode: String = "", onDone: () -> Unit) {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf(initialCode) }
    var password by remember { mutableStateOf("") }
    var needsPw by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Kommt die App über einen Link herein, gleich versuchen
    LaunchedEffect(initialCode) {
        if (initialCode.isNotBlank()) tryImport(ctx, initialCode, "", onOk = onDone, onNeedsPw = { needsPw = true }, onError = { error = it })
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(tx("Playlist empfangen")) },
        text = {
            Column {
                Text(tx("Füge den Code oder Link ein, den du bekommen hast."), fontSize = 12.sp, color = Color.Gray)
                OutlinedTextField(code, { code = it; error = null }, label = { Text(tx("Code")) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (needsPw) {
                    OutlinedTextField(password, { password = it; error = null }, label = { Text(tx("Passwort")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = code.isNotBlank(), onClick = {
                tryImport(ctx, code, password, onOk = onDone, onNeedsPw = { needsPw = true; error = tx("Dieser Code braucht ein Passwort.") }, onError = { error = it })
            }) { Text(tx("Hinzufügen")) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(tx("Abbrechen")) } },
    )
}

private fun tryImport(
    ctx: android.content.Context, code: String, password: String,
    onOk: () -> Unit, onNeedsPw: () -> Unit, onError: (String) -> Unit,
) {
    try {
        val res = Share.decode(code, password)
        var profile = res.profile
        // M3U-Datei: Inhalt in eine lokale Datei schreiben und darauf zeigen
        if (profile.type == SourceType.M3U_FILE) {
            val content = res.fileContent
            if (content.isNullOrBlank()) { onError(tx("Der Code enthält keine Datei.")); return }
            val dir = java.io.File(ctx.filesDir, "shared").apply { mkdirs() }
            val f = java.io.File(dir, "pl_${System.currentTimeMillis()}.m3u")
            f.writeText(content)
            profile = profile.copy(url = android.net.Uri.fromFile(f).toString())
        }
        Repo.saveProfile(profile)
        toast(ctx, tx("Playlist \"%s\" hinzugefügt", profile.name))
        onOk()
    } catch (e: Share.NeedsPassword) {
        onNeedsPw()
    } catch (e: Share.WrongPassword) {
        onError(tx("Falsches Passwort."))
    } catch (e: Exception) {
        onError(tx("Ungültiger Code."))
    }
}
