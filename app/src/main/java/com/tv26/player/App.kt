package com.tv26.player

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tv26.player.data.Net
import com.tv26.player.data.Repo
import com.tv26.player.data.tx

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_REC, tx("Aufnahmen"), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_REMIND, tx("Erinnerungen"), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    /** Bilder (Logos, Cover) laden über denselben Netzwerk-Client (sicheres DNS, VPN). */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient { Net.client }.crossfade(true).build()

    companion object {
        const val CH_REC = "rec"
        const val CH_REMIND = "remind"
    }
}
