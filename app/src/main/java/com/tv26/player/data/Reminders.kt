package com.tv26.player.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tv26.player.App
import com.tv26.player.R
import com.tv26.player.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Erinnerung an eine Sendung, 2 Minuten vor dem Start. */
object Reminders {
    fun schedule(ctx: Context, item: Item, p: Program) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val i = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("title", p.title).putExtra("channel", item.name).putExtra("time", p.start)
        val pi = PendingIntent.getBroadcast(
            ctx, (item.id + p.start).hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = maxOf(System.currentTimeMillis() + 1000, p.start - 2 * 60_000)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val time = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(i.getLongExtra("time", 0)))
        L.init(Store(c))
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(c, App.CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(tx("Gleich: %s", i.getStringExtra("title")))
            .setContentText(tx("%s auf %s", time, i.getStringExtra("channel")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(c).notify((System.currentTimeMillis() % 100000).toInt(), n)
        } catch (e: SecurityException) {
            // Keine Erlaubnis für Benachrichtigungen
        }
    }
}
