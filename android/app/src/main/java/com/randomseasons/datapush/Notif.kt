package com.randomseasons.datapush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object Notif {
    private const val CH_SERVICE = "service"
    // silent channel: the ping is played by Alert so it can be switched off and also works on TV
    const val CH_INCOMING = "incoming2"
    const val ID_SERVICE = 1
    private const val ID_INCOMING = 2
    private const val ID_PAIR = 3

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun channels(ctx: Context) {
        nm(ctx).createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Running in background", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps Random Push ready to receive"
                setShowBadge(false)
            })
        nm(ctx).deleteNotificationChannel("incoming")
        nm(ctx).createNotificationChannel(
            NotificationChannel(CH_INCOMING, "Received data", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Files and text pushed from your other devices"
                setSound(null, null)
                enableVibration(true)
            })
    }

    private fun activity(ctx: Context, cls: Class<*>, req: Int) = PendingIntent.getActivity(
        ctx, req, Intent(ctx, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun service(ctx: Context): Notification =
        Notification.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Random Push is ready to receive")
            .setContentText("Tap to push files or text")
            .setContentIntent(activity(ctx, MainActivity::class.java, 10))
            .setOngoing(true)
            .build()

    fun incoming(ctx: Context, title: String, body: String, textItemId: String?) {
        val b = Notification.Builder(ctx, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(activity(ctx, HistoryActivity::class.java, 11))
            .setAutoCancel(true)
        if (textItemId != null) {
            val pi = PendingIntent.getBroadcast(ctx, 12,
                Intent(ctx, CopyReceiver::class.java).putExtra("id", textItemId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, "Copy", pi).build())
        }
        try { nm(ctx).notify(ID_INCOMING, b.build()) } catch (_: SecurityException) {}
    }

    fun pair(ctx: Context, name: String) {
        val n = Notification.Builder(ctx, CH_INCOMING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Pairing request")
            .setContentText("$name wants to pair — tap to accept or decline")
            .setContentIntent(activity(ctx, MainActivity::class.java, 13))
            .setAutoCancel(true)
            .build()
        try { nm(ctx).notify(ID_PAIR, n) } catch (_: SecurityException) {}
    }

    fun clearIncoming(ctx: Context) = nm(ctx).cancel(ID_INCOMING)
    fun clearPair(ctx: Context) = nm(ctx).cancel(ID_PAIR)
}
