package com.randomseasons.datapush

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/** "Received" screen: received (and sent) items with Open / Copy / Share. */
class HistoryActivity : Activity() {
    private var tab = "in"
    private lateinit var list: LinearLayout
    private lateinit var tabIn: Button
    private lateinit var tabOut: Button
    private val listener: () -> Unit = { render() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun tv(text: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { click() }
    }

    private fun fmtSize(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1048576 -> "%.1f KB".format(n / 1024.0)
        n < 1073741824 -> "%.1f MB".format(n / 1048576.0)
        else -> "%.2f GB".format(n / 1073741824.0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PushService.start(this)
        Core.init(this)
        title = "History"
        tab = intent.getStringExtra("tab") ?: "in"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(24)) }
        tabIn = button("Received") { tab = "in"; render() }
        tabOut = button("Sent") { tab = "out"; render() }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tabIn); addView(tabOut)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(button("Clear list") {
                AlertDialog.Builder(this@HistoryActivity).setMessage("Clear the list? (files stay in Download/Random Push)")
                    .setPositiveButton("Clear") { _, _ ->
                        synchronized(Core.history) { Core.history.clear() }
                        Core.saveHistory(); Core.changed()
                    }.setNegativeButton("Cancel", null).show()
            })
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        Core.listeners.add(listener)
        render()
        Core.markSeen()
    }

    override fun onPause() {
        super.onPause()
        Core.listeners.remove(listener)
    }

    private fun open(it: Item) {
        val u = Uri.parse(it.uri)
        val apk = it.name.lowercase().endsWith(".apk")
        // An APK needs the "install unknown apps" permission, otherwise the installer closes without asking.
        if (apk && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Random Push to install apps, then tap Open again", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val type = if (apk) "application/vnd.android.package-archive" else contentResolver.getType(u) ?: Core.mimeOf(it.name)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show() }
    }

    private fun share(it: Item) {
        val u = Uri.parse(it.uri)
        val i = Intent(Intent.ACTION_SEND).setType(contentResolver.getType(u) ?: "*/*").putExtra(Intent.EXTRA_STREAM, u)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share"))
    }

    private fun render() {
        tabIn.alpha = if (tab == "in") 1f else 0.5f
        tabOut.alpha = if (tab == "out") 1f else 0.5f
        list.removeAllViews()
        val items = synchronized(Core.history) { Core.history.filter { it.dir == tab } }
        if (items.isEmpty()) list.addView(tv("Nothing here yet.").apply { alpha = 0.7f; setPadding(0, dp(16), 0, 0) })
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (it in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), if (it.dir == "in" && !it.seen) 0xFF1F9D55.toInt() else 0x55888888)
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            card.addView(tv((if (it.kind == "text") "📝 Text" else "📄 ${it.name}"), 15f, true))
            val via = when (it.via) { "LAN" -> " · local network"; "internet" -> " · internet relay"; else -> "" }
            card.addView(tv((if (it.dir == "in") "from " else "to ") + it.peer + " · " + df.format(Date(it.time)) +
                (if (it.kind == "file") " · " + fmtSize(it.size) else "") + via, 12f).apply { alpha = 0.7f })
            if (it.kind == "text") card.addView(tv(it.text).apply { setTextIsSelectable(true); setPadding(0, dp(6), 0, dp(4)) })
            if (!it.ok) card.addView(tv("Failed: ${it.error}", 12f).apply { setTextColor(0xFFD64545.toInt()) })
            val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (it.kind == "text") acts.addView(button("Copy") { Core.copyToClipboard(it.text); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show() })
            if (it.kind == "file" && it.dir == "in" && it.uri.isNotEmpty()) {
                acts.addView(button("Open") { open(it) })
                acts.addView(button("Share") { share(it) })
            }
            acts.addView(button("Remove") {
                synchronized(Core.history) { Core.history.removeAll { h -> h.id == it.id } }
                Core.saveHistory(); Core.changed()
            })
            card.addView(acts)
            list.addView(card)
        }
    }
}
