package com.randomseasons.datapush

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings as SysSettings
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class Peer(val id: String) {
    var name = ""
    var platform = ""
    val addrs = ArrayList<String>() // "ip:port", most recent first
    var lanSeen = 0L
    var relaySeen = 0L
    val lan get() = Core.now() - lanSeen < Core.LAN_ONLINE_MS
    val relay get() = Core.now() - relaySeen < Core.RELAY_ONLINE_MS
}

class Item {
    var id = ""; var dir = ""; var time = 0L; var peer = ""; var kind = ""; var name = ""; var size = 0L
    var uri = ""; var text = ""; var seen = false; var via = ""; var batch = ""; var ok = true; var error = ""

    fun toJson() = JSONObject().put("id", id).put("dir", dir).put("time", time).put("peer", peer).put("kind", kind)
        .put("name", name).put("size", size).put("uri", uri).put("text", text).put("seen", seen).put("via", via)
        .put("batch", batch).put("ok", ok).put("error", error)

    companion object {
        fun from(o: JSONObject) = Item().apply {
            id = o.optString("id"); dir = o.optString("dir"); time = o.optLong("time"); peer = o.optString("peer")
            kind = o.optString("kind"); name = o.optString("name"); size = o.optLong("size"); uri = o.optString("uri")
            text = o.optString("text"); seen = o.optBoolean("seen"); via = o.optString("via"); batch = o.optString("batch")
            ok = o.optBoolean("ok", true); error = o.optString("error")
        }
    }
}

class Foreign(val id: String, val name: String, val addr: String, val seen: Long, val hasKey: Boolean)

class Progress(
    val active: Boolean = false, val label: String = "", val done: Long = 0, val total: Long = 0,
    val result: String = "", val ok: Boolean = false,
)

class Incoming(val fromId: String, val fromName: String, val batch: String, val idx: Int, val count: Int, val via: String)
class Meta(val batch: String, val idx: Int, val count: Int)

/** One thing to push: text, or a file read from a content Uri (or a cached copy). */
class OutItem(val kind: String, val name: String, val uri: Uri?, val text: String, val size: Long) {
    fun open(ctx: Context): InputStream =
        if (kind == "text") text.toByteArray(Charsets.UTF_8).inputStream()
        else ctx.contentResolver.openInputStream(uri!!) ?: throw java.io.IOException("cannot read $name")
}

object Core {
    const val UDP_PORT = 47321
    const val TCP_PORT = 47322
    const val LAN_ONLINE_MS = 15_000L
    const val RELAY_ONLINE_MS = 15 * 60_000L
    const val PLATFORM = "android"
    private const val HISTORY_MAX = 300

    lateinit var app: Context
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    // settings (guarded by `this`)
    var deviceId = ""; var deviceName = ""; var key = ""
    var relayEnabled = true; var relayServer = "https://ntfy.sh"; var relayMaxMb = 15L
    var autoCopy = true; var keepWifi = true; var soundOn = true; var popupOn = true
    var relayLastTime = 0L
    val relayRecent = ArrayList<String>()

    val peers = LinkedHashMap<String, Peer>()   // guarded by itself
    val history = ArrayList<Item>()             // guarded by itself
    val foreign = HashMap<String, Foreign>()    // guarded by itself
    @Volatile var progress = Progress()
    @Volatile var relayStatus = "starting"
    @Volatile var pairReq: JSONObject? = null
    @Volatile var httpPort = TCP_PORT
    @Volatile var scanning = false
    val relayGen = AtomicLong(0)
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastEmit = 0L

    fun now() = System.currentTimeMillis()

    fun init(ctx: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        app = ctx.applicationContext
        load()
        applyWifiLock()
        Lan.start()
        Relay.start()
    }

    // ---------------- persistence ----------------

    private fun file(n: String) = File(app.filesDir, n)

    private fun readJson(n: String): String? = try { file(n).readText() } catch (e: Exception) { null }

    private fun writeJson(n: String, s: String) {
        try {
            val tmp = file("$n.tmp")
            tmp.writeText(s)
            tmp.renameTo(file(n))
        } catch (_: Exception) {
        }
    }

    private fun load() {
        readJson("settings.json")?.let {
            try {
                val o = JSONObject(it)
                deviceId = o.optString("device_id"); deviceName = o.optString("device_name"); key = o.optString("key")
                relayEnabled = o.optBoolean("relay_enabled", true); relayServer = o.optString("relay_server", "https://ntfy.sh")
                relayMaxMb = o.optLong("relay_max_mb", 15); autoCopy = o.optBoolean("auto_copy_text", true)
                keepWifi = o.optBoolean("keep_wifi", true); soundOn = o.optBoolean("sound", true); popupOn = o.optBoolean("popup", true)
                relayLastTime = o.optLong("relay_last_time")
                o.optJSONArray("relay_recent_ids")?.let { a -> for (i in 0 until a.length()) relayRecent.add(a.getString(i)) }
            } catch (_: Exception) {
            }
        }
        if (deviceId.isEmpty()) deviceId = Crypto.randomHex(6)
        if (deviceName.isBlank()) {
            deviceName = SysSettings.Global.getString(app.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
                ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        }
        readJson("peers.json")?.let {
            try {
                val a = JSONArray(it)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val p = Peer(o.getString("id"))
                    p.name = o.optString("name"); p.platform = o.optString("platform")
                    o.optJSONArray("addrs")?.let { x -> for (j in 0 until x.length()) p.addrs.add(x.getString(j)) }
                    p.lanSeen = o.optLong("lan_seen"); p.relaySeen = o.optLong("relay_seen")
                    peers[p.id] = p
                }
            } catch (_: Exception) {
            }
        }
        readJson("history.json")?.let {
            try {
                val a = JSONArray(it)
                for (i in 0 until a.length()) history.add(Item.from(a.getJSONObject(i)))
            } catch (_: Exception) {
            }
        }
        saveSettings()
    }

    fun saveSettings() {
        val o = synchronized(this) {
            JSONObject().put("device_id", deviceId).put("device_name", deviceName).put("key", key)
                .put("relay_enabled", relayEnabled).put("relay_server", relayServer).put("relay_max_mb", relayMaxMb)
                .put("auto_copy_text", autoCopy).put("keep_wifi", keepWifi).put("sound", soundOn).put("popup", popupOn).put("relay_last_time", relayLastTime)
                .put("relay_recent_ids", JSONArray(relayRecent))
        }
        writeJson("settings.json", o.toString())
    }

    fun savePeers() {
        val a = JSONArray()
        synchronized(peers) {
            for (p in peers.values) a.put(
                JSONObject().put("id", p.id).put("name", p.name).put("platform", p.platform)
                    .put("addrs", JSONArray(p.addrs)).put("lan_seen", p.lanSeen).put("relay_seen", p.relaySeen)
            )
        }
        writeJson("peers.json", a.toString())
    }

    fun saveHistory() {
        val a = JSONArray()
        synchronized(history) { for (i in history) a.put(i.toJson()) }
        writeJson("history.json", a.toString())
    }

    // ---------------- UI notifications ----------------

    fun changed() {
        main.post { for (l in listeners) l() }
    }

    private fun changedThrottled() {
        val n = now()
        if (n - lastEmit > 200) {
            lastEmit = n
            changed()
        }
    }

    fun keys() = Keys(synchronized(this) { key })

    fun unseen() = synchronized(history) { history.count { it.dir == "in" && !it.seen } }

    fun sortedPeers(): List<Peer> = synchronized(peers) {
        peers.values.sortedWith(compareBy({ if (it.lan) 0 else if (it.relay) 1 else 2 }, { it.name.lowercase() }))
    }

    fun foreignNow(): List<Foreign> = synchronized(foreign) { foreign.values.filter { now() - it.seen < LAN_ONLINE_MS * 2 } }

    fun applyWifiLock() {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (keepWifi && wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "datapush:wifi").apply {
                setReferenceCounted(false); acquire()
            }
        } else if (!keepWifi && wifiLock != null) {
            wifiLock?.release()
            wifiLock = null
        }
    }

    // ---------------- peers ----------------

    fun upsertPeer(id: String, name: String, platform: String, addr: String?, lan: Boolean, relayTime: Long) {
        if (id.isEmpty() || id == deviceId) return
        var save = false
        var change = false
        synchronized(peers) {
            val p = peers.getOrPut(id) { save = true; Peer(id) }
            if (name.isNotEmpty() && p.name != name) { p.name = name; save = true }
            if (platform.isNotEmpty() && p.platform != platform) { p.platform = platform; save = true }
            if (addr != null && p.addrs.firstOrNull() != addr) {
                p.addrs.remove(addr); p.addrs.add(0, addr)
                while (p.addrs.size > 4) p.addrs.removeAt(p.addrs.size - 1)
                save = true
            }
            if (lan) {
                if (!p.lan) change = true
                p.lanSeen = now()
            }
            if (relayTime > p.relaySeen) { p.relaySeen = relayTime; change = true }
        }
        synchronized(foreign) { foreign.remove(id) }
        if (save) savePeers()
        if (save || change) changed()
    }

    fun peerLanOnline(id: String) = synchronized(peers) { peers[id]?.lan ?: false }

    fun noteForeign(id: String, name: String, addr: String, hasKey: Boolean) {
        if (id.isEmpty() || id == deviceId) return
        val isNew = synchronized(foreign) { foreign.put(id, Foreign(id, name, addr, now(), hasKey)) == null }
        if (isNew) changed()
    }

    fun forgetPeer(id: String) {
        synchronized(peers) { peers.remove(id) }
        savePeers(); changed()
    }

    // ---------------- receiving ----------------

    fun copyToClipboard(text: String) {
        main.post {
            val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Random Push", text))
        }
    }

    fun receiveText(inc: Incoming, text: String) {
        if (autoCopy) copyToClipboard(text)
        addInItem(inc, Item().apply { kind = "text"; name = preview(text, 80); size = text.length.toLong(); this.text = text })
    }

    private fun sanitizeRel(name: String): List<String> {
        val parts = name.split('/', '\\').map { p ->
            p.map { c -> if ("<>:\"|?*".indexOf(c) >= 0 || c.code < 32) '_' else c }.joinToString("").trim().trimEnd('.')
        }.filter { it.isNotEmpty() && it != "." && it != ".." }
        return parts.ifEmpty { listOf("file") }
    }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Save into Download/Random Push (keeping sub-folders of pushed folders). Returns an error or null. */
    fun receiveFile(inc: Incoming, relName: String, input: InputStream, expected: Long?): String? {
        val parts = sanitizeRel(relName)
        val fileName = parts.last()
        val sub = parts.dropLast(1)
        val cr = app.contentResolver
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(fileName))
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Random Push" + (if (sub.isEmpty()) "" else "/" + sub.joinToString("/")))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try { cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) } catch (e: Exception) { null }
            ?: return "cannot create the file in Downloads"
        try {
            var n = 0L
            (cr.openOutputStream(uri) ?: throw java.io.IOException("cannot write")).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val want = if (expected != null) minOf(buf.size.toLong(), expected - n).toInt() else buf.size
                    if (want <= 0) break
                    val r = input.read(buf, 0, want)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    n += r
                }
            }
            if (expected != null && n < expected) {
                cr.delete(uri, null, null)
                return "transfer interrupted"
            }
            cv.clear(); cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, cv, null, null)
            var finalName = fileName
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) finalName = c.getString(0) ?: fileName
            }
            addInItem(inc, Item().apply {
                kind = "file"; name = (sub + finalName).joinToString("/"); size = n; this.uri = uri.toString()
            })
            return null
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            return e.message ?: "write failed"
        }
    }

    private fun addInItem(inc: Incoming, item: Item) {
        item.id = Crypto.randomHex(8); item.dir = "in"; item.time = now(); item.peer = inc.fromName
        item.via = inc.via; item.batch = "${inc.fromId}:${inc.batch}"; item.ok = true
        synchronized(history) {
            history.add(0, item)
            while (history.size > HISTORY_MAX) history.removeAt(history.size - 1)
        }
        saveHistory()
        if (inc.idx + 1 >= inc.count) notifyBatch(inc)
        changed()
    }

    private fun notifyBatch(inc: Incoming) {
        val key = "${inc.fromId}:${inc.batch}"
        val items = synchronized(history) { history.filter { it.dir == "in" && it.batch == key } }
        if (items.isEmpty()) return
        if (items.size == 1 && items[0].kind == "text") {
            Alert.received(app, "Text from ${inc.fromName}", preview(items[0].text, 300), items[0].id)
        } else {
            Alert.received(app, "${items.size} item(s) from ${inc.fromName}",
                preview(items.reversed().joinToString(", ") { it.name }, 300), null)
        }
    }

    fun markSeen() {
        synchronized(history) { for (i in history) i.seen = true }
        saveHistory(); changed()
        Notif.clearIncoming(app)
    }

    fun preview(s: String, n: Int): String {
        val one = s.trim().split(Regex("\\s+")).joinToString(" ")
        return if (one.length > n) one.take(n) + "…" else one
    }

    // ---------------- pushing ----------------

    fun setDone(done: Long) {
        val p = progress
        progress = Progress(p.active, p.label, done, p.total, p.result, p.ok)
        changedThrottled()
    }

    private fun setLabel(l: String) {
        val p = progress
        progress = Progress(p.active, l, p.done, p.total, p.result, p.ok)
        changed()
    }

    /** Start pushing; returns an error message or null. */
    fun startPush(targetId: String, uris: List<Uri>, text: String): String? {
        if (progress.active) return "A push is already running"
        if (keys().token.isEmpty()) return "Set the sync key first (Settings)"
        val peer = synchronized(peers) { peers[targetId] } ?: return "Choose a device to send to"
        if (uris.isEmpty() && text.isBlank()) return "Add files or type text first"
        progress = Progress(true, "Preparing…")
        changed()
        thread {
            val items = ArrayList<OutItem>()
            if (text.isNotBlank()) items.add(OutItem("text", "", null, text, text.toByteArray(Charsets.UTF_8).size.toLong()))
            for (u in uris) describe(u)?.let { items.add(it) }
            runPush(peer, items)
        }
        return null
    }

    fun nameAndSize(u: Uri): Pair<String, Long> {
        var name = u.lastPathSegment ?: "file"
        var size = -1L
        if (u.scheme == "file") { u.path?.let { val f = java.io.File(it); if (f.exists()) size = f.length() }; return name to size }
        try {
            app.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (_: Exception) {
        }
        return name to size
    }

    /** Size is needed up front; when the provider doesn't know it, copy to cache first. */
    private fun describe(u: Uri): OutItem? {
        val (name, size) = nameAndSize(u)
        if (size >= 0) return OutItem("file", name, u, "", size)
        return try {
            val f = File(app.cacheDir, "out_" + Crypto.randomHex(4) + "_" + name.replace('/', '_'))
            app.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            OutItem("file", name, Uri.fromFile(f), "", f.length())
        } catch (e: Exception) {
            null
        }
    }

    private fun runPush(peer: Peer, items: List<OutItem>) {
        val total = items.sumOf { it.size }
        progress = Progress(true, "Pushing to ${peer.name}…", 0, total)
        changed()
        val batch = Crypto.randomHex(6)
        var lanAlive = true
        var nLan = 0; var nRelay = 0
        val errors = ArrayList<String>()
        var base = 0L
        items.forEachIndexed { i, it ->
            val meta = Meta(batch, i, items.size)
            val label = if (it.kind == "text") "text" else it.name
            var via: String? = null
            var err: String? = null
            if (lanAlive) {
                setLabel("Sending $label over the local network (${i + 1}/${items.size})")
                val addrs = synchronized(peers) { peers[peer.id]?.addrs?.toList() ?: emptyList() }
                var reached = false
                for (a in addrs.take(3)) {
                    setDone(base)
                    when (val r = Lan.sendItem(a, it, meta, base)) {
                        is Lan.Result.Ok -> { via = "LAN"; reached = true; promoteAddr(peer.id, a) }
                        is Lan.Result.Rejected -> { err = r.msg; reached = true }
                        is Lan.Result.Unreachable -> {}
                    }
                    if (reached) break
                }
                if (!reached) lanAlive = false
            }
            if (via == null && err == null && relayEnabled) {
                setLabel("Sending $label via internet relay (${i + 1}/${items.size})")
                setDone(base)
                err = Relay.sendItem(peer.id, it, meta)
                if (err == null) via = "internet"
            }
            if (via == null && err == null) {
                err = if (relayEnabled) "device unreachable" else "not reachable on the local network (internet relay is off)"
            }
            base += it.size
            setDone(base)
            if (via == "LAN") nLan++ else if (via != null) nRelay++
            val item = Item().apply {
                id = Crypto.randomHex(8); dir = "out"; time = now(); this.peer = peer.name; kind = it.kind
                name = if (it.kind == "text") preview(it.text, 80) else it.name; size = it.size
                text = if (it.kind == "text") it.text.take(20_000) else ""; seen = true; this.via = via ?: ""
                this.batch = batch; ok = via != null; error = err ?: ""
            }
            synchronized(history) {
                history.add(0, item)
                while (history.size > HISTORY_MAX) history.removeAt(history.size - 1)
            }
            err?.let { e -> errors.add("$label: $e") }
            if (it.uri?.scheme == "file") it.uri.path?.let { p -> File(p).delete() }
        }
        saveHistory()
        val parts = ArrayList<String>()
        if (nLan > 0) parts.add("$nLan over local network")
        if (nRelay > 0) parts.add("$nRelay via internet relay")
        var result = if (parts.isEmpty()) "Push to ${peer.name} failed" else "Pushed to ${peer.name}: ${parts.joinToString(", ")}"
        if (errors.isNotEmpty()) result += " — ${errors.size} failed: ${errors.joinToString("; ")}"
        progress = Progress(false, "", base, base, result, errors.isEmpty())
        changed()
    }

    private fun promoteAddr(id: String, addr: String) = synchronized(peers) {
        peers[id]?.let { it.addrs.remove(addr); it.addrs.add(0, addr) }
    }

    // ---------------- settings ----------------

    fun updateSettings(name: String?, newKey: String?, relay: Boolean?, server: String?, maxMb: Long?, copy: Boolean?, wifi: Boolean?, sound: Boolean? = null, popup: Boolean? = null) {
        var restart = false
        synchronized(this) {
            if (!name.isNullOrBlank()) deviceName = name.trim()
            if (newKey != null) {
                if (Crypto.norm(newKey) != Crypto.norm(key)) {
                    restart = true
                    synchronized(foreign) { foreign.clear() }
                }
                key = newKey.trim()
            }
            if (relay != null) { if (relay != relayEnabled) restart = true; relayEnabled = relay }
            if (server != null) { if (server.trim() != relayServer) restart = true; relayServer = server.trim() }
            if (maxMb != null) relayMaxMb = maxMb.coerceIn(1, 2048)
            if (copy != null) autoCopy = copy
            if (wifi != null) keepWifi = wifi
            if (sound != null) soundOn = sound
            if (popup != null) popupOn = popup
        }
        if (restart) relayGen.incrementAndGet()
        applyWifiLock()
        saveSettings(); changed()
    }

    fun answerPair(accept: Boolean) {
        val r = pairReq
        pairReq = null
        if (accept && r != null) {
            updateSettings(null, r.optString("key"), null, null, null, null, null)
            upsertPeer(r.optString("id"), r.optString("name"), r.optString("plat"), r.optString("addr").ifEmpty { null }, true, 0)
        }
        Notif.clearPair(app)
        changed()
    }
}
