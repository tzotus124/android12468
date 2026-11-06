package com.wolrelay

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RelayService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        Relay.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("relay", "Relay status", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "relay").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Wake Relay is listening").setContentText("Ready to wake your PC")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        Relay.start()
        return START_STICKY
    }

    override fun onDestroy() { Relay.stop(); super.onDestroy() }

    companion object {
        fun start(c: Context) { c.startForegroundService(Intent(c, RelayService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, RelayService::class.java)) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Prefs.init(c)
        if (i.action == Intent.ACTION_BOOT_COMPLETED && Prefs.paired) RelayService.start(c)
    }
}
