package com.randomseasons.datapush

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val staged = ArrayList<Uri>()
    private var target = ""
    private var lastResult = ""
    private var pairDialog: AlertDialog? = null

    private lateinit var recvBtn: Button
    private lateinit var keyCard: LinearLayout
    private lateinit var keyEdit: EditText
    private lateinit var battCard: LinearLayout
    private lateinit var stagedBox: LinearLayout
    private lateinit var textEdit: EditText
    private lateinit var devBox: LinearLayout
    private lateinit var foreignBox: LinearLayout
    private lateinit var scanBtn: Button
    private lateinit var progBar: ProgressBar
    private lateinit var progLabel: TextView
    private lateinit var pushBtn: Button
    private lateinit var footer: TextView
    private lateinit var lastBox: LinearLayout
    private var lastSig = ""

    private val listener: () -> Unit = { refresh() }
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 4000) }
    }

    // ---------------- small view helpers ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun tv(text: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { click() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0x55888888) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    /** Visible focus for D-pad (TV) navigation. */
    private fun focusBg() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x552F6FED) })
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x332F6FED) })
        addState(intArrayOf(), GradientDrawable().apply { setColor(0x00000000) })
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v)
    }

    private fun weight1(v: View) = v.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun fmtSize(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1048576 -> "%.1f KB".format(n / 1024.0)
        n < 1073741824 -> "%.1f MB".format(n / 1048576.0)
        else -> "%.2f GB".format(n / 1073741824.0)
    }

    private fun ago(ms: Long): String {
        if (ms == 0L) return "never seen"
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    // ---------------- lifecycle ----------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PushService.start(this)
        Core.init(this)
        title = "Random Push"
        target = getPreferences(MODE_PRIVATE).getString("target", "") ?: ""
        buildUi()
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Core.listeners.add(listener)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        Core.listeners.remove(listener)
        handler.removeCallbacks(tick)
    }

    /** Things shared to Random Push from other apps are staged, ready to push. */
    private fun handleIntent(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val u = i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) addUris(listOf(u))
                else i.getStringExtra(Intent.EXTRA_TEXT)?.let { textEdit.setText(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { addUris(it) }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "History").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "Settings").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, HistoryActivity::class.java))
            2 -> showSettings()
        }
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK || data == null) return
        val list = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) list.add(c.getItemAt(i).uri) }
        if (list.isEmpty()) data.data?.let { list.add(it) }
        addUris(list)
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(24))
        }

        recvBtn = button("") { startActivity(Intent(this, HistoryActivity::class.java)) }.apply {
            setTextColor(Color.WHITE); textSize = 16f
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF1F9D55.toInt()) }
            visibility = View.GONE
        }
        col.addView(recvBtn)

        keyCard = card().apply {
            addView(tv("Set the sync key", 16f, true))
            addView(tv("Open Random Push on your PC — the key is shown there (Settings). Type it here, or press Pair on the PC when this phone appears in its list."))
            keyEdit = EditText(context).apply { hint = "XXXX-XXXX-XXXX-XXXX"; isSingleLine = true; inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
            addView(row(weight1(keyEdit), button("Save") {
                if (Crypto.norm(keyEdit.text.toString()).isEmpty()) toast("Type the key first")
                else Core.updateSettings(null, keyEdit.text.toString(), null, null, null, null, null)
            }))
        }
        col.addView(keyCard)

        battCard = card().apply {
            addView(tv("To receive while the screen is off, let Random Push run in the background."))
            addView(button("Allow background running") {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { toast("Not available on this device") }
                }
            })
        }
        col.addView(battCard)

        // what to push
        val drop = card()
        drop.addView(tv("What to push", 16f, true))
        drop.addView(button("＋ Add files") {
            val open = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            val get = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try {
                @Suppress("DEPRECATION") startActivityForResult(open, 7)
            } catch (e: Exception) {
                try {
                    @Suppress("DEPRECATION") startActivityForResult(get, 7)
                } catch (e2: Exception) {
                    toast("No file picker on this device — share files to Random Push from another app instead")
                }
            }
        })
        stagedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        drop.addView(stagedBox)
        textEdit = EditText(this).apply {
            hint = "…or type / paste text to push (sent together with the files)"
            minLines = 3; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        drop.addView(textEdit)
        col.addView(drop)

        // targets
        val dev = card()
        scanBtn = button("Scan") { Lan.scan() }
        dev.addView(row(weight1(tv("Send to", 16f, true)), scanBtn, button("Add IP") { addIp() }))
        devBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dev.addView(devBox)
        foreignBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dev.addView(foreignBox)
        col.addView(dev)

        progBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        col.addView(progBar)
        progLabel = tv("", 13f)
        col.addView(progLabel)
        pushBtn = button("PUSH") { push() }.apply {
            textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF2F6FED.toInt()) }
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) }
        }
        col.addView(pushBtn)
        lastBox = card().apply { visibility = View.GONE }
        col.addView(lastBox)
        footer = tv("", 12f).apply { alpha = 0.7f; setPadding(0, dp(12), 0, 0) }
        col.addView(footer)

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(col) })
        renderStaged()
    }

    private fun addUris(list: List<Uri>) {
        for (u in list) if (u !in staged) staged.add(u)
        renderStaged()
    }

    private fun renderStaged() {
        stagedBox.removeAllViews()
        for (u in staged.toList()) {
            val (name, size) = Core.nameAndSize(u)
            stagedBox.addView(row(
                weight1(tv("📄 $name" + if (size >= 0) "  ·  ${fmtSize(size)}" else "")),
                button("✕") { staged.remove(u); renderStaged() }.apply { minWidth = 0; minimumWidth = 0 }
            ))
        }
    }

    private fun push() {
        val text = textEdit.text.toString()
        if (staged.isEmpty() && text.isBlank()) return toast("Add files or type text first")
        if (target.isEmpty()) return toast("Choose a device to send to")
        lastResult = ""
        Core.startPush(target, staged.toList(), text)?.let { toast(it) }
    }

    private fun addIp() {
        val e = EditText(this).apply { hint = "192.168.1.23"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("IP address of the other device").setView(e)
            .setPositiveButton("Add") { _, _ ->
                val ip = e.text.toString().trim()
                thread {
                    val err = Lan.probe(ip, 2500)
                    runOnUiThread { toast(err ?: "Added") }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun refresh() {
        val n = Core.unseen()
        recvBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
        recvBtn.text = "📥 Received $n new — open"
        keyCard.visibility = if (Crypto.norm(Core.key).isEmpty()) View.VISIBLE else View.GONE
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        battCard.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE

        // devices
        val peers = Core.sortedPeers()
        if (peers.none { it.id == target }) target = (peers.firstOrNull { it.lan || it.relay } ?: peers.firstOrNull())?.id ?: ""
        devBox.removeAllViews()
        if (peers.isEmpty()) devBox.addView(tv("No devices yet. Open Random Push on your PC on the same Wi-Fi, use the same sync key (or Pair), then tap Scan.").apply { alpha = 0.7f })
        for (p in peers) {
            val st = when {
                p.lan -> "● online · same network"
                p.relay -> "● online · via internet"
                else -> "○ offline (last seen ${ago(maxOf(p.lanSeen, p.relaySeen))}) · will try anyway"
            }
            val rb = RadioButton(this).apply { isChecked = p.id == target; isClickable = false }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(tv((if (p.platform == "android") "📱 " else "🖥️ ") + p.name.ifEmpty { p.id }, 15f, true))
                addView(tv(st + (p.addrs.firstOrNull()?.let { " · $it" } ?: ""), 12f).apply {
                    setTextColor(if (p.lan) 0xFF1F9D55.toInt() else if (p.relay) 0xFF2F6FED.toInt() else 0xFF888888.toInt())
                })
            }
            devBox.addView(row(rb, weight1(info)).apply {
                setPadding(dp(4), dp(6), dp(4), dp(6))
                isFocusable = true
                background = focusBg()
                setOnClickListener {
                    target = p.id
                    getPreferences(MODE_PRIVATE).edit().putString("target", target).apply()
                    refresh()
                }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity).setMessage("Forget ${p.name}?")
                        .setPositiveButton("Forget") { _, _ -> Core.forgetPeer(p.id) }.setNegativeButton("Cancel", null).show()
                    true
                }
            })
        }
        foreignBox.removeAllViews()
        for (f in Core.foreignNow()) {
            foreignBox.addView(row(
                weight1(tv("Found ${f.name} (${f.addr}) — not paired" + if (f.hasKey) " (different key)" else "", 13f)),
                button("Pair") {
                    thread {
                        val err = Lan.sendPair(f.addr)
                        runOnUiThread { toast(err?.let { "Pair failed: $it" } ?: "Pair request sent — accept it on the other device") }
                    }
                }
            ))
        }
        scanBtn.text = if (Core.scanning) "Scanning…" else "Scan"
        scanBtn.isEnabled = !Core.scanning

        // progress
        val p = Core.progress
        pushBtn.isEnabled = !p.active
        pushBtn.alpha = if (p.active) 0.5f else 1f
        progBar.visibility = if (p.active) View.VISIBLE else View.GONE
        if (p.active) {
            progBar.progress = if (p.total > 0) (1000 * p.done / p.total).toInt() else 0
            progLabel.setTextColor(0xFF888888.toInt())
            progLabel.text = "${p.label}  ${fmtSize(p.done)} / ${fmtSize(p.total)}"
        } else if (p.result.isNotEmpty()) {
            progLabel.setTextColor(if (p.ok) 0xFF1F9D55.toInt() else 0xFFD64545.toInt())
            progLabel.text = p.result
            if (p.result != lastResult) {
                lastResult = p.result
                if (p.ok) { staged.clear(); textEdit.setText(""); renderStaged() }
            }
        }

        renderLast()

        val ips = Lan.localIps()
        footer.text = "This phone: ${Core.deviceName} · ${if (ips.isEmpty()) "no Wi-Fi" else ips.joinToString(", ") + " : " + Core.httpPort}\nRelay: ${Core.relayStatus}"

        val req = Core.pairReq
        if (req != null && pairDialog?.isShowing != true) {
            pairDialog = AlertDialog.Builder(this).setTitle("Pairing request")
                .setMessage("\"${req.optString("name", "A device")}\" wants to pair with this phone. Accepting replaces this phone's sync key with theirs.")
                .setPositiveButton("Accept") { _, _ -> Core.answerPair(true); toast("Paired") }
                .setNegativeButton("Decline") { _, _ -> Core.answerPair(false) }
                .setCancelable(false).show()
        }
    }

    /** Latest sent batch, refreshed after every push; the full list stays in History. */
    private fun renderLast() {
        val all = synchronized(Core.history) { Core.history.filter { it.dir == "out" } }
        val first = all.firstOrNull()
        val items = if (first == null) emptyList() else all.filter { it.batch == first.batch }.reversed()
        val sig = items.joinToString { it.id + it.ok }
        if (sig == lastSig) return
        lastSig = sig
        lastBox.removeAllViews()
        lastBox.visibility = if (first == null) View.GONE else View.VISIBLE
        if (first == null) return
        val okN = items.count { it.ok }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(first.time))
        lastBox.addView(row(
            weight1(tv("Last push · to ${first.peer} · $time · $okN/${items.size} delivered", 14f, true)),
            button("All") { startActivity(Intent(this, HistoryActivity::class.java).putExtra("tab", "out")) }
        ))
        for (h in items) {
            val via = if (!h.ok) "failed: ${h.error}" else if (h.via == "LAN") "local network" else "internet relay"
            val label = tv((if (h.ok) "✓ " else "✗ ") + (if (h.kind == "text") "📝 " else "📄 ") + h.name +
                (if (h.kind == "file") " · ${fmtSize(h.size)}" else "") + " · $via", 13f).apply {
                setTextColor(if (h.ok) 0xFF1F9D55.toInt() else 0xFFD64545.toInt())
            }
            if (h.kind == "text") {
                lastBox.addView(row(weight1(label), button("Copy") { Core.copyToClipboard(h.text); toast("Copied") }))
            } else {
                lastBox.addView(label.apply { setPadding(0, dp(4), 0, dp(4)) })
            }
        }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val name = EditText(this).apply { setText(Core.deviceName); isSingleLine = true }
        val key = EditText(this).apply { setText(Core.key); isSingleLine = true; inputType = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        val relay = CheckBox(this).apply { text = "Use the internet relay when devices can't reach each other directly (end-to-end encrypted)"; isChecked = Core.relayEnabled }
        val server = EditText(this).apply { setText(Core.relayServer); isSingleLine = true; hint = "https://ntfy.sh" }
        val max = EditText(this).apply { setText(Core.relayMaxMb.toString()); inputType = InputType.TYPE_CLASS_NUMBER }
        val copy = CheckBox(this).apply { text = "Copy received text to the clipboard automatically"; isChecked = Core.autoCopy }
        val wifi = CheckBox(this).apply { text = "Keep Wi-Fi awake while the screen is off (faster receiving)"; isChecked = Core.keepWifi }
        val sound = CheckBox(this).apply { text = "Play a ping sound when something arrives"; isChecked = Core.soundOn }
        val popup = CheckBox(this).apply { text = "Show a popup when something arrives (TV, or when notifications are off)"; isChecked = Core.popupOn }
        box.addView(tv("This device's name")); box.addView(name)
        box.addView(tv("Sync key (same key on every device)"))
        box.addView(row(weight1(key),
            button("Copy") { Core.copyToClipboard(key.text.toString()); toast("Key copied") },
            button("New") { key.setText(Crypto.newKey()) }))
        box.addView(copy); box.addView(sound); box.addView(popup)
        if (!Settings.canDrawOverlays(this)) box.addView(button("Allow popups over other apps") {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                toast("This device has no setting for it — popups will show as short messages instead")
            }
        })
        box.addView(wifi); box.addView(relay)
        box.addView(tv("Relay server (ntfy)")); box.addView(server)
        box.addView(tv("Max file size via relay (MB)")); box.addView(max)
        box.addView(tv("Received files are saved in Download/Random Push.\nPorts: TCP ${Core.httpPort}, UDP ${Core.UDP_PORT} · id ${Core.deviceId}", 12f).apply { alpha = 0.7f; setPadding(0, dp(10), 0, 0) })
        AlertDialog.Builder(this).setTitle("Settings").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                Core.updateSettings(name.text.toString(), key.text.toString(), relay.isChecked, server.text.toString(),
                    max.text.toString().toLongOrNull(), copy.isChecked, wifi.isChecked, sound.isChecked, popup.isChecked)
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
