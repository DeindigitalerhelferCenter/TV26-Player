package com.tv26.player.data

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec

/**
 * Playlist teilen: in einen kompakten Code packen (und wieder auslesen).
 * Kein Server nötig. Der Code enthält den Zugang (Adresse, MAC/Xtream usw.).
 * Optional mit Passwort verschlüsselt. Bei M3U-Datei wird der Datei-Inhalt mitgepackt.
 */
object Share {
    private const val PREFIX = "TV26."

    /** Baut den Teil-Code. password leer = ohne Verschlüsselung (der Code selbst ist das Geheimnis). */
    fun encode(p: Profile, fileContent: String? = null, password: String = ""): String {
        val o = JSONObject()
            .put("n", p.name).put("t", p.type.name).put("u", p.url)
            .put("us", p.user).put("pw", p.pass).put("e", p.epgUrl)
        if (fileContent != null) o.put("file", fileContent)
        val raw = gzip(o.toString().toByteArray(Charsets.UTF_8))
        val body = if (password.isBlank()) {
            "0" + b64(raw)
        } else {
            val salt = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val enc = cipher(Cipher.ENCRYPT_MODE, password, salt, iv).doFinal(raw)
            "1" + b64(salt) + "." + b64(iv) + "." + b64(enc)
        }
        return PREFIX + body
    }

    class Result(val profile: Profile, val fileContent: String?)
    class NeedsPassword : Exception()
    class BadCode : Exception()

    /** Liest einen Teil-Code. Wirft NeedsPassword, wenn der Code ein Passwort braucht. */
    fun decode(code: String, password: String = ""): Result {
        // Code kann auch in einem Link stecken (tv26://s/<code>) oder mit Leerzeichen eingefügt sein
        val clean = extract(code)
        val body = clean.removePrefix(PREFIX)
        val raw = try {
            when (body.firstOrNull()) {
                '0' -> ungzip(unb64(body.substring(1)))
                '1' -> {
                    if (password.isBlank()) throw NeedsPassword()
                    val parts = body.substring(1).split(".")
                    if (parts.size != 3) throw BadCode()
                    val dec = cipher(Cipher.DECRYPT_MODE, password, unb64(parts[0]), unb64(parts[1])).doFinal(unb64(parts[2]))
                    ungzip(dec)
                }
                else -> throw BadCode()
            }
        } catch (e: NeedsPassword) {
            throw e
        } catch (e: Exception) {
            throw if (body.startsWith("1")) WrongPassword() else BadCode()
        }
        val o = JSONObject(String(raw, Charsets.UTF_8))
        val type = runCatching { SourceType.valueOf(o.getString("t")) }.getOrDefault(SourceType.M3U_URL)
        val profile = Profile(
            id = UUID.randomUUID().toString(),
            name = o.optString("n").ifBlank { tx(type.label) },
            type = type, url = o.optString("u"), user = o.optString("us"),
            pass = o.optString("pw"), epgUrl = o.optString("e"),
        )
        return Result(profile, o.optString("file").ifBlank { null })
    }

    class WrongPassword : Exception()

    /** Code aus Text oder Link herausholen. */
    fun extract(text: String): String {
        val t = text.trim()
        Regex("""tv26://s/(\S+)""", RegexOption.IGNORE_CASE).find(t)?.let { return it.groupValues[1] }
        val i = t.indexOf(PREFIX)
        if (i >= 0) return t.substring(i).filterNot { it.isWhitespace() }
        return t.filterNot { it.isWhitespace() }
    }

    fun looksLikeCode(text: String) = text.contains(PREFIX) || text.contains("tv26://s/", ignoreCase = true)

    /** Als Link, damit Scannen des QR-Codes die App direkt öffnet. */
    fun asLink(code: String) = "tv26://s/" + code.removePrefix(PREFIX)

    // --- Hilfsfunktionen ---
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun unb64(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun ungzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }

    private fun cipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray): Cipher {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 60_000, 256)).encoded
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        }
    }
}
