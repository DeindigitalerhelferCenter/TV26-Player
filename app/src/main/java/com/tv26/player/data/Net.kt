package com.tv26.player.data

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Alle Netzwerk-Anfragen der App (Playlists, EPG, Bilder, Player) laufen über einen gemeinsamen Client.
 * Optional mit sicherem DNS (DNS über HTTPS), damit der Internet-Anbieter IPTV-Server nicht per DNS sperren kann.
 */
object Net {
    const val DEFAULT_UA = "TV26Player/1.0 (Android)"

    @Volatile var userAgent = DEFAULT_UA

    /** DNS-Anbieter: Name -> (DoH-Adresse, feste IP-Adressen zum Starten). "auto" = Standard. */
    val DNS_PROVIDERS = linkedMapOf(
        "auto" to ("" to emptyList()),
        "system" to ("" to emptyList()),
        "cloudflare" to ("https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")),
        "google" to ("https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")),
        "quad9" to ("https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112")),
        "adguard" to ("https://dns.adguard-dns.com/dns-query" to listOf("94.140.14.14", "94.140.15.15")),
    )

    @Volatile var client: OkHttpClient = build("auto")
        private set

    fun setDns(mode: String) {
        client = build(mode)
    }

    private fun build(mode: String): OkHttpClient {
        val base = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true) // auch http -> https
            .build()
        val dns = when (mode) {
            // Automatisch: Cloudflare -> Google -> normales DNS. So funktioniert die App immer,
            // auch wenn ein DNS-Dienst gesperrt ist.
            "auto" -> ChainDns(listOfNotNull(doh(base, "cloudflare"), doh(base, "google"), Dns.SYSTEM))
            else -> doh(base, mode) ?: return base
        }
        return base.newBuilder().dns(dns).build()
    }

    private fun doh(base: OkHttpClient, name: String): Dns? {
        val (url, ips) = DNS_PROVIDERS[name] ?: return null
        if (url.isEmpty()) return null
        return DnsOverHttps.Builder()
            .client(base)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
            .build()
    }

    /** Probiert mehrere DNS-Dienste nacheinander, bis einer antwortet. */
    private class ChainDns(private val chain: List<Dns>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            var last: Exception? = null
            for (d in chain) {
                try {
                    val r = d.lookup(hostname)
                    if (r.isNotEmpty()) return r
                } catch (e: Exception) {
                    last = e
                }
            }
            throw (last as? java.net.UnknownHostException) ?: java.net.UnknownHostException(hostname)
        }
    }

    /** Öffnet eine URL. Folgt Weiterleitungen und entpackt gzip automatisch. */
    fun open(url: String, headers: Map<String, String> = emptyMap(), readTimeout: Int = 60_000): InputStream {
        val c = client.newBuilder()
            .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(20_000, readTimeout).toLong(), TimeUnit.MILLISECONDS)
            .build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val resp = c.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException(tx("Server antwortet mit HTTP %s", resp.code))
        }
        val body = resp.body ?: throw IOException(tx("Server antwortet mit HTTP %s", resp.code))
        return gunzipIfNeeded(BufferedInputStream(body.byteStream()))
    }

    fun text(url: String, headers: Map<String, String> = emptyMap(), readTimeout: Int = 60_000): String =
        open(url, headers, readTimeout).bufferedReader().use { it.readText() }

    private fun gunzipIfNeeded(s: BufferedInputStream): InputStream {
        s.mark(2)
        val a = s.read()
        val b = s.read()
        s.reset()
        return if (a == 0x1f && b == 0x8b) GZIPInputStream(s) else s
    }
}
