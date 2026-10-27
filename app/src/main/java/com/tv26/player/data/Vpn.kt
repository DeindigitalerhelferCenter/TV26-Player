package com.tv26.player.data

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader

/**
 * Eingebautes VPN (WireGuard-kompatibel). Der Nutzer bringt die Konfiguration seines VPN-Anbieters mit.
 * Der Tunnel gilt NUR für TV26 (Split-Tunnel): andere Apps bleiben normal.
 */
object Vpn {
    enum class State { OFF, CONNECTING, ON, ERROR }

    data class Conf(val name: String, val text: String)

    var state by mutableStateOf(State.OFF)
    var message by mutableStateOf("")
    /** z. B. "185.1.2.3 · DE" */
    var publicIp by mutableStateOf("")

    private val backend by lazy { GoBackend(Repo.app) }
    private val tunnel = object : Tunnel {
        override fun getName() = "tv26"
        override fun onStateChange(newState: Tunnel.State) {
            Repo.scope.launch { if (newState == Tunnel.State.DOWN && state == State.ON) state = State.OFF }
        }
    }

    // --- Gespeicherte Konfigurationen (z. B. "DE", "TR") ---
    fun list(): List<Conf> = runCatching {
        val a = JSONArray(Repo.store.str("vpn_confs", "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> Conf(o.getString("name"), o.getString("conf")) } }
    }.getOrDefault(emptyList())

    private fun saveList(l: List<Conf>) =
        Repo.store.put("vpn_confs", JSONArray(l.map { JSONObject().put("name", it.name).put("conf", it.text) }).toString())

    /** Neue Konfiguration speichern. Prüft vorher, ob sie gültig ist. */
    fun add(name: String, text: String) {
        Config.parse(BufferedReader(StringReader(text))) // wirft eine Ausnahme, wenn ungültig
        saveList(list().filter { it.name != name } + Conf(name, text))
        if (selected.isBlank()) selected = name
    }

    fun remove(name: String) {
        saveList(list().filter { it.name != name })
        if (selected == name) selected = list().firstOrNull()?.name.orEmpty()
    }

    var selected: String
        get() = Repo.store.str("vpn_selected")
        set(v) = Repo.store.put("vpn_selected", v)

    var autoStart: Boolean
        get() = Repo.store.bool("vpn_auto", true)
        set(v) = Repo.store.putBool("vpn_auto", v)

    /** Schutz: nur abspielen, wenn das VPN verbunden ist (damit nie die echte IP durchrutscht). */
    var required: Boolean
        get() = Repo.store.bool("vpn_required", false)
        set(v) = Repo.store.putBool("vpn_required", v)

    /** Android fragt beim ersten Mal, ob die App ein VPN aufbauen darf. null = schon erlaubt. */
    fun permissionIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

    suspend fun connect(name: String = selected): Boolean {
        val conf = list().find { it.name == name } ?: run {
            state = State.ERROR
            message = tx("Keine VPN-Konfiguration gewählt")
            return false
        }
        state = State.CONNECTING
        message = ""
        return try {
            withContext(Dispatchers.IO) {
                val cfg = Config.parse(BufferedReader(StringReader(onlyThisApp(conf.text))))
                backend.setState(tunnel, Tunnel.State.UP, cfg)
                // Wichtig: schon offene Verbindungen laufen noch AUSSERHALB des Tunnels -> alle schließen,
                // damit ab jetzt jede Verbindung neu durch das VPN aufgebaut wird (kein IP-Leck)
                Net.client.connectionPool.evictAll()
            }
            selected = name
            state = State.ON
            checkIp()
            true
        } catch (e: Exception) {
            state = State.ERROR
            message = e.message ?: e.javaClass.simpleName
            false
        }
    }

    suspend fun disconnect() {
        runCatching {
            withContext(Dispatchers.IO) {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                Net.client.connectionPool.evictAll()
            }
        }
        state = State.OFF
        publicIp = ""
        checkIp()
    }

    /** Öffentliche IP und Land prüfen – zeigt, ob das VPN wirklich wirkt. */
    suspend fun checkIp() {
        publicIp = runCatching {
            withContext(Dispatchers.IO) {
                val o = JSONObject(Net.text("https://ipinfo.io/json", readTimeout = 10_000))
                listOf(o.s("ip"), o.s("country"), o.s("city")).filter { it.isNotBlank() }.joinToString(" · ")
            }
        }.getOrDefault("")
    }

    /** Split-Tunnel: nur der Verkehr von TV26 geht durch das VPN. */
    private fun onlyThisApp(text: String): String {
        val lines = text.lines().filterNot {
            val k = it.substringBefore('=').trim().lowercase()
            k == "includedapplications" || k == "excludedapplications"
        }.toMutableList()
        val i = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (i >= 0) lines.add(i + 1, "IncludedApplications = ${Repo.app.packageName}")
        return lines.joinToString("\n")
    }
}
