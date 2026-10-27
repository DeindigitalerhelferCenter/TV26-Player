package com.tv26.player.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Sprache der App: nur Deutsch ("de") und Türkisch ("tr"). */
object L {
    var lang by mutableStateOf("de")

    val locale: Locale get() = if (lang == "tr") Locale("tr", "TR") else Locale.GERMANY

    fun init(store: Store) {
        lang = store.str("lang").ifBlank { if (Locale.getDefault().language == "tr") "tr" else "de" }
    }

    fun set(store: Store, l: String) {
        lang = l
        store.put("lang", l)
    }
}

/**
 * Übersetzt einen deutschen Text. Der deutsche Text ist der Schlüssel.
 * Mit Argumenten wird %s ersetzt (wie String.format).
 */
fun tx(de: String, vararg args: Any?): String {
    val s = if (L.lang == "tr") TR[de] ?: de else de
    return if (args.isEmpty()) s else String.format(L.locale, s, *args)
}

private val TR = mapOf(
    // Allgemein
    "Schließen" to "Kapat",
    "Abbrechen" to "İptal",
    "Speichern" to "Kaydet",
    "Gespeichert" to "Kaydedildi",
    "Zurück" to "Geri",
    "Weiter" to "İleri",
    "Fehler" to "Hata",
    "Fehler: %s" to "Hata: %s",
    "Erneut versuchen" to "Tekrar dene",
    "Öffnen" to "Aç",
    "Öffnen mit" to "Şununla aç",
    "Kein externer Player gefunden" to "Harici oynatıcı bulunamadı",
    "Extern" to "Harici",
    "Extern öffnen" to "Harici aç",
    "Favorit" to "Favori",
    "★ Favorit" to "★ Favori",
    "☆ Favorit" to "☆ Favori",
    "Gesehen" to "İzlendi",
    "✓ Gesehen" to "✓ İzlendi",
    "Andere" to "Diğer",
    "Kanal" to "Kanal",
    "OK" to "Tamam",
    "Fertig" to "Tamam",
    "Bearbeiten" to "Düzenle",
    "Löschen" to "Sil",
    "Name" to "Ad",
    "Datei" to "Dosya",

    // Typen
    "M3U-Link" to "M3U linki",
    "M3U-Datei" to "M3U dosyası",
    "Xtream Codes" to "Xtream Codes",
    "Live" to "Canlı",
    "Film" to "Film",
    "Serie" to "Dizi",

    // MAC-Portal
    "MAC-Portal" to "MAC Portal",
    "MAC-ID" to "MAC ID",
    "MAC-Adresse" to "MAC adresi",
    "MAC-Login fehlgeschlagen. Bitte Portal-Adresse und MAC prüfen." to
        "MAC girişi başarısız. Lütfen portal adresini ve MAC adresini kontrol et.",
    "Konto gesperrt" to "Hesap engellendi",
    "Portal-Adresse (z. B. http://portal.com:8080/c/)" to "Portal adresi (ör. http://portal.com:8080/c/)",
    "Ungültige MAC-Adresse (Format: 00:1A:79:12:34:56)" to "Geçersiz MAC adresi (biçim: 00:1A:79:12:34:56)",
    "Deine Geräte-MAC-ID: %s" to "Cihaz MAC ID'n: %s",
    "Gib diese MAC-ID deinem Anbieter, damit er sie freischaltet." to
        "Bu MAC ID'yi sağlayıcına ver, o da etkinleştirsin.",
    "Geräte-MAC-ID einsetzen" to "Cihaz MAC ID'sini kullan",
    "Link konnte nicht erstellt werden" to "Bağlantı oluşturulamadı",
    "Filme und Serien gibt es bei MAC-Portalen noch nicht." to "MAC portallarında film ve diziler henüz yok.",
    "Das TV-Programm wird automatisch geladen und alle 6 Stunden erneuert. Mehrere EPG-Adressen mit Komma trennen." to
        "Yayın akışı otomatik yüklenir ve 6 saatte bir yenilenir. Birden fazla EPG adresini virgülle ayır.",

    // Verlauf, Favorit im Player, Filme/Serien
    "Verlauf" to "Geçmiş",
    "Verlauf löschen" to "Geçmişi sil",
    "Verlauf wirklich löschen?" to "Geçmiş silinsin mi?",
    "Nur Liste löschen" to "Sadece listeyi sil",
    "Alles löschen (auch Weiterschauen und Gesehen)" to "Hepsini sil (devam noktaları ve izlendi işaretleri dahil)",
    "Noch nichts angesehen." to "Henüz hiçbir şey izlenmedi.",
    "Entfernen" to "Kaldır",
    "Zu Favoriten hinzugefügt" to "Favorilere eklendi",
    "Aus Favoriten entfernt" to "Favorilerden çıkarıldı",
    "  ·  OK halten = Favorit" to "  ·  OK basılı tut = Favori",
    "Filme und Serien werden geladen… %s" to "Filmler ve diziler yükleniyor… %s",
    "Folge %s" to "%s. Bölüm",
    "Verbindung wird erneuert…" to "Bağlantı yenileniyor…",
    // Ansicht, Backup-Datum, Bilder
    "Ansicht ändern" to "Görünümü değiştir",
    // Teilen / Empfangen
    "Teilen" to "Paylaş",
    "Empfangen" to "Al",
    "Playlist teilen" to "Playlisti paylaş",
    "Playlist empfangen" to "Playlist al",
    "Der Freund öffnet \"Playlist empfangen\" und scannt den QR-Code oder gibt den Code ein." to
        "Arkadaşın \"Playlist al\" açar, QR kodu okutur veya kodu girer.",
    "Passwort (optional)" to "Şifre (isteğe bağlı)",
    "Zu groß für einen QR-Code. Bitte den Code unten senden." to "QR kod için çok büyük. Lütfen aşağıdaki kodu gönder.",
    "Sag dem Freund das Passwort getrennt (nicht im selben Chat)." to "Şifreyi arkadaşına ayrı söyle (aynı sohbette değil).",
    "Öffne das in TV26: %s" to "Bunu TV26'da aç: %s",
    "Füge den Code oder Link ein, den du bekommen hast." to "Aldığın kodu veya bağlantıyı yapıştır.",
    "Code" to "Kod",
    "Passwort" to "Şifre",
    "Dieser Code braucht ein Passwort." to "Bu kod bir şifre gerektiriyor.",
    "Hinzufügen" to "Ekle",
    "Playlist \"%s\" hinzugefügt" to "\"%s\" playlisti eklendi",
    "Der Code enthält keine Datei." to "Kod bir dosya içermiyor.",
    "Falsches Passwort." to "Yanlış şifre.",
    "Ungültiger Code." to "Geçersiz kod.",
    "Kanäle" to "Kanallar",
    "  ·  ▲▼ Kanal wechseln" to "  ·  ▲▼ Kanal değiştir",
    "  ·  OK = Kanalliste" to "  ·  OK = Kanal listesi",
    "  ·  Zahlen = Kanal-Nummer" to "  ·  Rakamlar = Kanal numarası",
    "Auf diesem Gerät sind höchstens 2 Bilder möglich." to "Bu cihazda en fazla 2 ekran mümkün.",
    // VPN, DNS, TMDB
    "VPN" to "VPN",
    "Sicheres DNS" to "Güvenli DNS",
    "Automatisch (empfohlen)" to "Otomatik (önerilen)",
    "VPN ist optional. Ist eine Konfiguration da, verbindet die App beim Start automatisch. Ohne VPN schützt die App automatisch mit sicherem DNS." to
        "VPN isteğe bağlıdır. Yapılandırma varsa uygulama açılışta otomatik bağlanır. VPN olmadan uygulama otomatik olarak güvenli DNS ile korur.",
    "Verbunden" to "Bağlı",
    "Verbinde…" to "Bağlanıyor…",
    "Sichtbare IP: %s" to "Görünen IP: %s",
    "Noch keine VPN-Konfiguration. Lade die WireGuard-Konfiguration (.conf) deines VPN-Anbieters, z. B. für Deutschland oder die Türkei." to
        "Henüz VPN yapılandırması yok. VPN sağlayıcının WireGuard yapılandırmasını (.conf) yükle, ör. Almanya veya Türkiye için.",
    "Datei (.conf)" to "Dosya (.conf)",
    "Text einfügen" to "Metin yapıştır",
    "Trennen" to "Bağlantıyı kes",
    "Verbinden" to "Bağlan",
    "IP prüfen" to "IP kontrol et",
    "VPN beim Start automatisch verbinden" to "Açılışta VPN'e otomatik bağlan",
    "Nur mit VPN abspielen (Schutz)" to "Sadece VPN ile oynat (koruma)",
    "Das VPN gilt nur für TV26, andere Apps bleiben normal." to "VPN sadece TV26 için geçerli, diğer uygulamalar normal kalır.",
    "VPN-Konfiguration einfügen" to "VPN yapılandırmasını yapıştır",
    "Name (z. B. DE oder TR)" to "Ad (ör. DE veya TR)",
    "VPN-Konfiguration gespeichert" to "VPN yapılandırması kaydedildi",
    "Ungültige VPN-Konfiguration: %s" to "Geçersiz VPN yapılandırması: %s",
    "VPN wurde nicht erlaubt" to "VPN'e izin verilmedi",
    "Keine VPN-Konfiguration gewählt" to "VPN yapılandırması seçilmedi",
    "VPN ist nicht verbunden. Zum Schutz deiner IP wird nichts abgespielt." to
        "VPN bağlı değil. IP adresini korumak için hiçbir şey oynatılmıyor.",
    "System" to "Sistem",
    "Sicheres DNS hilft gegen DNS-Sperren deines Internet-Anbieters. Es versteckt deine IP nicht – dafür ist das VPN da. Gilt nach \"Neu laden\"." to
        "Güvenli DNS, internet sağlayıcının DNS engellerine karşı yardımcı olur. IP adresini gizlemez – bunun için VPN var. \"Yenile\" sonrası geçerli.",
    "Cover für Filme und Serien (TMDB)" to "Film ve dizi kapakları (TMDB)",
    "TMDB API-Schlüssel (optional)" to "TMDB API anahtarı (isteğe bağlı)",
    "Kostenlos auf themoviedb.org → Konto → Einstellungen → API. Ohne Schlüssel nutzt die App nur die Bilder aus deiner Quelle." to
        "themoviedb.org → Hesap → Ayarlar → API üzerinden ücretsiz. Anahtar olmadan uygulama sadece kaynağındaki resimleri kullanır.",
    "Lizenzen" to "Lisanslar",
    "Backup vom %s geladen" to "%s tarihli yedek yüklendi",
    // Verwaltung (einfach, mit OK)
    "Nur ausgeblendete" to "Sadece gizlenenler",
    "Nur gesperrte" to "Sadece kilitliler",
    "Alle anzeigen" to "Hepsini göster",
    "Alle ausblenden" to "Hepsini gizle",
    "18+ finden und sperren" to "18+ bul ve kilitle",
    "Gruppe" to "Grup",
    "Eintrag" to "Öğe",
    "Anzeigen" to "Göster",
    "18+ (PIN)" to "18+ (PIN)",
    "%s Änderungen" to "%s değişiklik",
    "Keine Änderungen" to "Değişiklik yok",
    "OK – Speichern" to "Tamam – Kaydet",
    "Änderungen speichern?" to "Değişiklikler kaydedilsin mi?",
    "Verwerfen" to "Vazgeç",
    "Einzelne Kanäle, Filme und Serien markierst du mit langem Drücken in der Liste." to
        "Tek tek kanal, film ve dizileri listede uzun basarak işaretlersin.",
    // Verwaltung, 18+, Backup
    "Ausgeblendete und gesperrte Einträge" to "Gizlenen ve kilitlenen öğeler",
    "Einzelne Einträge" to "Tek tek öğeler",
    "Gruppe suchen…" to "Grup ara…",
    "Alle auswählen" to "Tümünü seç",
    "Auswahl aufheben" to "Seçimi kaldır",
    "18+ finden" to "18+ bul",
    "%s ausgewählt" to "%s seçildi",
    "Einblenden" to "Göster",
    "18+ sperren" to "18+ kilitle",
    "Entsperren" to "Kilidi aç",
    "ausgeblendet" to "gizli",
    "18+ gesperrt" to "18+ kilitli",
    "Aus Favoriten entfernen" to "Favorilerden çıkar",
    "Zu Favoriten hinzufügen" to "Favorilere ekle",
    "Wieder einblenden" to "Tekrar göster",
    "Ausblenden (aus Listen entfernen)" to "Gizle (listelerden kaldır)",
    "18+ Sperre entfernen" to "18+ kilidini kaldır",
    "18+ sperren (PIN)" to "18+ kilitle (PIN)",
    "Ganze Gruppe ausblenden" to "Tüm grubu gizle",
    "Gruppen-Sperre entfernen" to "Grup kilidini kaldır",
    "Ganze Gruppe 18+ sperren" to "Tüm grubu 18+ kilitle",
    "Backup in Download speichern" to "Yedeği Download klasörüne kaydet",
    "Backup gespeichert: %s" to "Yedek kaydedildi: %s",
    "Dieses Backup-Format (z. B. TiviMate .tmb) wird noch nicht unterstützt. Bitte eine TV26-Backup-Datei (.json) wählen." to
        "Bu yedek biçimi (ör. TiviMate .tmb) henüz desteklenmiyor. Lütfen bir TV26 yedek dosyası (.json) seç.",
    "Der Stream startet nicht. Bitte erneut versuchen." to "Yayın başlamıyor. Lütfen tekrar dene.",
    "Der Server meldet: Zugang wird gerade benutzt (407). Läuft dieselbe MAC oder derselbe Zugang auf einem anderen Gerät?" to
        "Sunucu bildiriyor: Hesap şu an kullanımda (407). Aynı MAC veya hesap başka bir cihazda açık mı?",

    // Laden
    "Lade Playlist…" to "Playlist yükleniyor…",
    "Anmelden…" to "Giriş yapılıyor…",
    "Live-Kanäle laden…" to "Canlı kanallar yükleniyor…",
    "Filme laden…" to "Filmler yükleniyor…",
    "Serien laden…" to "Diziler yükleniyor…",
    "Datei kann nicht gelesen werden" to "Dosya okunamıyor",
    "Die Playlist ist leer oder ungültig" to "Playlist boş veya geçersiz",
    "Keine EPG-Quelle. Du kannst eine EPG-URL bei der Playlist eintragen." to
        "EPG kaynağı yok. Playlist ayarlarına bir EPG adresi ekleyebilirsin.",
    "TV-Programm wird geladen…" to "Yayın akışı yükleniyor…",
    "Kein TV-Programm gefunden" to "Yayın akışı bulunamadı",
    "EPG-Fehler: %s" to "EPG hatası: %s",
    "Weiterleitung ohne Ziel" to "Hedefsiz yönlendirme",
    "Server antwortet mit HTTP %s" to "Sunucu HTTP %s hatası verdi",
    "Zu viele Weiterleitungen" to "Çok fazla yönlendirme",
    "Login fehlgeschlagen. Bitte Server, Benutzer und Passwort prüfen." to
        "Giriş başarısız. Lütfen sunucu, kullanıcı adı ve şifreyi kontrol et.",
    "Konto-Status: %s" to "Hesap durumu: %s",

    // Erinnerung, Aufnahme
    "Gleich: %s" to "Birazdan: %s",
    "%s auf %s" to "%2\$s, saat %1\$s",
    "Aufnahmen" to "Kayıtlar",
    "Erinnerungen" to "Hatırlatmalar",
    "Aufnahme" to "Kayıt",
    "Aufnahme läuft (%s)" to "Kayıt devam ediyor (%s)",
    "Alle stoppen" to "Hepsini durdur",
    "Speicher nicht verfügbar" to "Depolama kullanılamıyor",
    "Datei kann nicht geschrieben werden" to "Dosya yazılamıyor",
    "Aufnahme gestoppt" to "Kayıt durduruldu",
    "Aufnahme fehlgeschlagen: %s" to "Kayıt başarısız: %s",
    "Aufnahme beendet (Verbindung verloren)" to "Kayıt bitti (bağlantı koptu)",
    "Aufnahme leer – nichts gespeichert. %s" to "Kayıt boş – hiçbir şey kaydedilmedi. %s",
    "Dein Anbieter erlaubt nur eine Verbindung. Du kannst nicht gleichzeitig schauen und aufnehmen." to
        "Sağlayıcın sadece bir bağlantıya izin veriyor. Aynı anda izleyip kaydedemezsin.",
    "Falls dein Anbieter nur eine Verbindung erlaubt, geht Schauen und Aufnehmen nicht gleichzeitig." to
        "Sağlayıcın sadece bir bağlantıya izin veriyorsa, aynı anda izleyip kaydedemezsin.",
    "Aufnahme gestartet (Download/TV26)" to "Kayıt başladı (Download/TV26)",

    // Player
    "Wiedergabe-Fehler: %s" to "Oynatma hatası: %s",
    "Aus" to "Kapalı",
    "%s Kan." to "%s kanal",
    "Spur %s" to "Parça %s",
    "Titel: %s" to "Başlık: %s",
    "Video" to "Video",
    "Audio" to "Ses",
    "%s Kanäle" to "%s kanal",
    "Puffer: %s s" to "Tampon: %s sn",
    "Länge: %s" to "Süre: %s",
    "☀ Helligkeit %s %%" to "☀ Parlaklık %%%s",
    "🔊 Lautstärke %s %%" to "🔊 Ses %%%s",
    "Audiospur" to "Ses parçası",
    "Untertitel" to "Altyazı",
    "Keine Auswahl möglich" to "Seçim yok",
    "Bildformat" to "Görüntü formatı",
    "Anpassen" to "Sığdır",
    "Strecken (Vollbild)" to "Uzat (tam ekran)",
    "Zoom (Zuschneiden)" to "Yakınlaştır (kırp)",
    "Volle Breite" to "Tam genişlik",
    "Volle Höhe" to "Tam yükseklik",
    "Geschwindigkeit" to "Hız",
    "Sleep-Timer" to "Uyku zamanlayıcı",
    "%s Minuten" to "%s dakika",
    "Stream-Info" to "Yayın bilgisi",
    "Jetzt: %s–%s  %s" to "Şimdi: %s–%s  %s",
    "Danach: %s  %s" to "Sonra: %s  %s",
    "⏱ %s min" to "⏱ %s dk",
    "Format" to "Format",
    "Tempo" to "Hız",
    "Timer" to "Zamanlayıcı",
    "Programm" to "Yayın akışı",
    "Stopp REC" to "Kaydı durdur",
    "Bild-im-Bild" to "Pencerede",
    "Info" to "Bilgi",
    "LIVE" to "CANLI",
    "  ·  ▲▼ Kanal wechseln  ·  Zahlen = Kanal-Nummer" to "  ·  ▲▼ Kanal değiştir  ·  Rakamlar = Kanal numarası",

    // Multi-Screen
    "Multi-Screen" to "Çoklu ekran",
    "2 Bilder" to "2 ekran",
    "4 Bilder" to "4 ekran",
    "Kanal wählen" to "Kanal seç",
    "Kanal ändern" to "Kanalı değiştir",
    "Suchen…" to "Ara…",

    // Listen, Dialoge
    "Archiv" to "Arşiv",
    "TV-Programm" to "Yayın akışı",
    "Kinderschutz" to "Çocuk kilidi",
    "Diese Gruppe ist gesperrt. Bitte PIN eingeben." to "Bu grup kilitli. Lütfen PIN gir.",
    "PIN" to "PIN",
    "Falsche PIN" to "Yanlış PIN",
    "Für diesen Kanal gibt es kein TV-Programm." to "Bu kanal için yayın akışı yok.",
    "Erinnerung gesetzt: %s" to "Hatırlatma kuruldu: %s",
    "LIVE ▶" to "CANLI ▶",
    "Archiv ▶" to "Arşiv ▶",
    "🔔 Erinnern" to "🔔 Hatırlat",
    "Regie: %s" to "Yönetmen: %s",
    "Darsteller: %s" to "Oyuncular: %s",
    "Fortsetzen ab %s" to "Devam et (%s)",
    "Abspielen" to "Oynat",
    "Von vorne" to "Baştan başla",

    // Playlists
    "Playlist hinzufügen" to "Playlist ekle",
    "Playlist bearbeiten" to "Playlist düzenle",
    "Noch keine Playlist.\n\nFüge einen M3U/M3U8-Link, eine M3U-Datei oder einen Xtream-Codes-Zugang hinzu." to
        "Henüz playlist yok.\n\nBir M3U/M3U8 linki, M3U dosyası veya Xtream Codes hesabı ekle.",
    "Playlist löschen?" to "Playlist silinsin mi?",
    "\"%s\" wird gelöscht." to "\"%s\" silinecek.",
    "M3U / M3U8 URL" to "M3U / M3U8 adresi",
    " Datei gewählt ✓" to " Dosya seçildi ✓",
    " Datei wählen" to " Dosya seç",
    "Server (z. B. http://server.com:8080)" to "Sunucu (ör. http://server.com:8080)",
    "Benutzername" to "Kullanıcı adı",
    "Passwort" to "Şifre",
    "EPG-URL (optional, XMLTV)" to "EPG adresi (isteğe bağlı, XMLTV)",
    "Ohne EPG-URL nimmt die App das TV-Programm aus der Playlist (url-tvg) oder vom Xtream-Server." to
        "EPG adresi yoksa uygulama yayın akışını playlistten (url-tvg) veya Xtream sunucusundan alır.",

    // Startseite
    "Live-TV" to "Canlı TV",
    "Filme" to "Filmler",
    "Serien" to "Diziler",
    "Favoriten" to "Favoriler",
    "Suche" to "Ara",
    "TV-Guide" to "Yayın Rehberi",
    "Einstellungen" to "Ayarlar",
    "Alle" to "Tümü",
    "Zuletzt gesehen" to "Son izlenenler",
    "Neu laden" to "Yenile",
    "Playlist wechseln" to "Playlist değiştir",
    "Fehler beim Laden" to "Yükleme hatası",
    "Keine Filme in dieser Playlist" to "Bu playlistte film yok",
    "Keine Serien" to "Dizi yok",
    "Serien gibt es bei Xtream-Codes. Bei M3U findest du Serien unter \"Filme\"." to
        "Diziler Xtream Codes ile gelir. M3U'da diziler \"Filmler\" bölümündedir.",
    "Keine Kanäle" to "Kanal yok",
    "Keine Einträge" to "Kayıt yok",
    "Keine Folgen gefunden" to "Bölüm bulunamadı",
    "Staffel %s" to "%s. Sezon",
    "Weiter ab %s" to "Devam: %s",
    "Noch keine Favoriten.\nTippe bei einem Kanal, Film oder einer Serie auf den Stern ☆." to
        "Henüz favori yok.\nBir kanal, film veya dizide yıldıza ☆ dokun.",
    "Live-Kanäle" to "Canlı kanallar",
    "Kanal, Film oder Serie suchen…" to "Kanal, film veya dizi ara…",
    "Nichts gefunden" to "Hiçbir şey bulunamadı",
    "EPG neu laden" to "EPG'yi yenile",
    "Kein Programm" to "Yayın yok",

    // Einstellungen
    "Sprache" to "Dil",
    "Backup gespeichert" to "Yedek kaydedildi",
    "Backup geladen" to "Yedek yüklendi",
    "Keine TV26-Backup-Datei" to "TV26 yedek dosyası değil",
    "Wiedergabe" to "Oynatma",
    "Live-Format (nur Xtream)" to "Canlı format (sadece Xtream)",
    "Gilt nach \"Neu laden\"" to "\"Yenile\" sonrası geçerli",
    "Bild-im-Bild beim Verlassen der App" to "Uygulamadan çıkınca pencerede oynat",
    "Letzte Playlist beim Start öffnen" to "Açılışta son playlisti aç",
    "User-Agent (leer = Standard)" to "User-Agent (boş = varsayılan)",
    "Größe: %s %%" to "Boyut: %%%s",
    "Weiß" to "Beyaz",
    "Gelb" to "Sarı",
    "Grün" to "Yeşil",
    "Cyan" to "Turkuaz",
    "Design" to "Tasarım",
    "Keine PIN gesetzt." to "PIN belirlenmedi.",
    "PIN ist gesetzt. Gesperrte Gruppen brauchen die PIN." to "PIN belirlendi. Kilitli gruplar PIN ister.",
    " PIN festlegen" to " PIN belirle",
    " Gruppen verwalten" to " Grupları yönet",
    "Gesperrte Gruppen wieder sperren" to "Kilitli grupları tekrar kilitle",
    "Werkzeuge" to "Araçlar",
    "Test läuft…" to "Test çalışıyor…",
    " Internet-Speedtest" to " İnternet hız testi",
    " EPG neu laden" to " EPG'yi yenile",
    "EPG wird neu geladen" to "EPG yenileniyor",
    " Backup speichern" to " Yedeği kaydet",
    " Backup laden" to " Yedeği yükle",
    "Das Backup enthält Playlists, Favoriten, Verlauf und Einstellungen." to
        "Yedek; playlistleri, favorileri, geçmişi ve ayarları içerir.",
    "Aufnahmen werden im Ordner Download/TV26 als .ts-Datei gespeichert." to
        "Kayıtlar Download/TV26 klasörüne .ts dosyası olarak kaydedilir.",
    "Stoppen" to "Durdur",
    "Über" to "Hakkında",
    "Spielt deine eigenen M3U/M3U8-Playlists und Xtream-Codes-Zugänge ab. Die App enthält selbst keine Sender." to
        "Kendi M3U/M3U8 playlistlerini ve Xtream Codes hesaplarını oynatır. Uygulamanın kendi kanalı yoktur.",
    "PIN festlegen" to "PIN belirle",
    "Alte PIN" to "Eski PIN",
    "Neue PIN (leer = keine PIN)" to "Yeni PIN (boş = PIN yok)",
    "Alte PIN ist falsch" to "Eski PIN yanlış",
    "PIN gespeichert" to "PIN kaydedildi",
    "Gruppen verwalten" to "Grupları yönet",
    "👁 = anzeigen / ausblenden   🔒 = mit PIN sperren" to "👁 = göster / gizle   🔒 = PIN ile kilitle",
    "Ausblenden" to "Gizle",
    "Sperren" to "Kilitle",
    "Bitte zuerst eine PIN festlegen" to "Lütfen önce bir PIN belirle",
)
