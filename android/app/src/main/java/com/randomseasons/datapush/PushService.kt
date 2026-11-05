package com.randomseasons.datapush

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Foreground service = the Android "tray": keeps the receiver, discovery and relay running. */
class PushService : Service() {
    override fun onCreate() {
        super.onCreate()
        Notif.channels(this)
        val n = Notif.service(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notif.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notif.ID_SERVICE, n)
        Core.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, PushService::class.java)) } catch (_: Exception) {}
        }
    }
}
