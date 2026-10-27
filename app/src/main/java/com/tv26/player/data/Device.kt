package com.tv26.player.data

import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

/** Was für ein Gerät ist das? (Fernseher/Fire TV, schwacher Stick, Touch, Bild-im-Bild) */
object Device {
    /** Fernseher: Android TV, Google TV oder Fire TV */
    var isTv = false
        private set
    var isFireTv = false
        private set
    /** Wenig Arbeitsspeicher (z. B. Fire TV Stick Lite): sparsamer arbeiten */
    var lowEnd = false
        private set
    var hasTouch = true
        private set
    /** Bild-im-Bild gibt es z. B. auf Fire TV nicht */
    var hasPip = false
        private set

    fun init(ctx: Context) {
        val pm = ctx.packageManager
        isFireTv = pm.hasSystemFeature("amazon.hardware.fire_tv")
        val ui = ctx.getSystemService(UiModeManager::class.java)
        isTv = isFireTv || ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        hasTouch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        hasPip = Build.VERSION.SDK_INT >= 26 && pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        lowEnd = am.isLowRamDevice || mi.totalMem < 2_200_000_000L
    }
}
