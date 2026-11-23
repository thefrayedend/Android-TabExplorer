package com.tabexplorer.android

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps sharing on while the app is closed ("Ready to receive"), with a small notification, since Android stops background apps. */
class ShareService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OpService.createChannel(this)
        val n = NotificationCompat.Builder(this, OpService.CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("TabExplorer is ready to receive").setContentText("Your PCs can send files to this phone.").setOngoing(true).setSilent(true)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)).build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(OpService.NOTE_SHARE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(OpService.NOTE_SHARE, n)
        (application as App).refreshSharing()
        return START_STICKY
    }
}
