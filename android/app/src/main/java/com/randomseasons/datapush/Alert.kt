package com.randomseasons.datapush

import android.app.NotificationManager
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Outside notification: system notification (heads-up on phones) + own ping sound.
 * Where heads-up can't show (Android TV / notifications switched off) a popup is drawn
 * over other apps when allowed, otherwise a toast.
 */
object Alert {
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    fun isTv(ctx: Context) =
        (ctx.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    private fun headsUpWorks(ctx: Context): Boolean {
        if (isTv(ctx)) return false
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return false
        val ch = nm.getNotificationChannel(Notif.CH_INCOMING) ?: return false
        return ch.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    fun received(ctx: Context, title: String, body: String, textItemId: String?) {
        Notif.incoming(ctx, title, body, textItemId)
        fire(ctx, title, body)
    }

    fun pair(ctx: Context, name: String) {
        Notif.pair(ctx, name)
        fire(ctx, "Pairing request", "$name wants to pair — open Random Push to accept")
    }

    private fun fire(ctx: Context, title: String, body: String) {
        main.post {
            if (Core.soundOn) ping(ctx)
            if (Core.popupOn && !headsUpWorks(ctx)) popup(ctx, title, body)
        }
    }

    fun ping(ctx: Context) {
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            ctx.resources.openRawResourceFd(R.raw.ping).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
        }
    }

    private fun popup(ctx: Context, title: String, body: String) {
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "$title\n$body", Toast.LENGTH_LONG).show()
            return
        }
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        val d = ctx.resources.displayMetrics.density
        val tv = isTv(ctx)
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(0xEE1D2129.toInt())
                setStroke((3 * d).toInt(), 0xFF1F9D55.toInt())
            }
            addView(TextView(ctx).apply {
                text = "⇅  $title"; setTextColor(0xFFFFFFFF.toInt()); textSize = if (tv) 20f else 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(ctx).apply {
                text = body; setTextColor(0xFFC8CDD8.toInt()); textSize = if (tv) 16f else 14f; maxLines = 3
            })
            setOnClickListener {
                ctx.startActivity(Intent(ctx, HistoryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                try { wm.removeView(this) } catch (_: Exception) {}
            }
        }
        val lp = WindowManager.LayoutParams(
            (if (tv) 460 else 320).times(d).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (24 * d).toInt(); y = (24 * d).toInt()
        }
        try {
            wm.addView(v, lp)
            overlay = v
            main.postDelayed({
                try { wm.removeView(v) } catch (_: Exception) {}
                if (overlay === v) overlay = null
            }, 8000)
        } catch (_: Exception) {
            Toast.makeText(ctx, "$title\n$body", Toast.LENGTH_LONG).show()
        }
    }
}
