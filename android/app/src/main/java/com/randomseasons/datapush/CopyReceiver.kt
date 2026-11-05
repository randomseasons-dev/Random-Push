package com.randomseasons.datapush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** "Copy" button on a received-text notification. */
class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Core.init(ctx)
        val id = intent.getStringExtra("id") ?: return
        val text = synchronized(Core.history) { Core.history.firstOrNull { it.id == id }?.text } ?: return
        Core.copyToClipboard(text)
        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
        Notif.clearIncoming(ctx)
    }
}
