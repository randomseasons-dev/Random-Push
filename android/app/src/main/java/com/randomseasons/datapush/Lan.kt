package com.randomseasons.datapush

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import kotlin.concurrent.thread

/** Local network: tiny HTTP server, UDP broadcast discovery, subnet scan, direct push. */
object Lan {
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        val wm = Core.app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("datapush").apply { setReferenceCounted(false); acquire() }
        startServer()
        startUdp()
    }

    fun info(): JSONObject = JSONObject().put("app", "datapush").put("v", 1).put("group", Core.keys().group)
        .put("id", Core.deviceId).put("name", Core.deviceName).put("plat", Core.PLATFORM).put("port", Core.httpPort)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    /** IPv4 addresses of Wi-Fi / Ethernet / hotspot interfaces (mobile data is skipped). */
    fun localIfaces(): List<Pair<Inet4Address, InetAddress?>> {
        val out = ArrayList<Pair<Inet4Address, InetAddress?>>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces() ?: return out)) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name.lowercase()
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") || n.startsWith("dummy")) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address
                    if (a is Inet4Address && !a.isLinkLocalAddress && !a.isLoopbackAddress) out.add(a to ia.broadcast)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun localIps() = localIfaces().map { it.first.hostAddress ?: "" }

    // ---------------- HTTP server ----------------

    private fun startServer() = thread(name = "dp-http") {
        var ss: ServerSocket? = null
        for (p in Core.TCP_PORT until Core.TCP_PORT + 10) {
            try {
                ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p)) }
                Core.httpPort = p
                break
            } catch (_: IOException) {
            }
        }
        val server = ss ?: return@thread
        while (true) {
            try {
                val s = server.accept()
                thread { try { handle(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {} } }
            } catch (_: Exception) {
                Thread.sleep(500)
            }
        }
    }

    private fun readHead(ins: InputStream): String? {
        val b = ByteArrayOutputStream()
        var m = 0
        while (b.size() < 32 * 1024) {
            val c = ins.read()
            if (c < 0) return null
            b.write(c)
            m = when {
                (m == 0 || m == 2) && c == '\r'.code -> m + 1
                (m == 1 || m == 3) && c == '\n'.code -> m + 1
                c == '\r'.code -> 1
                else -> 0
            }
            if (m == 4) return b.toString("ISO-8859-1")
        }
        return null
    }

    private class Limited(val inner: InputStream, var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val r = inner.read()
            if (r >= 0) left--
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
    }

    private class Chunked(val inner: InputStream) : InputStream() {
        var left = 0L
        var done = false
        private fun line(): String {
            val sb = StringBuilder()
            while (true) {
                val c = inner.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) sb.append(c.toChar())
            }
            return sb.toString()
        }
        private fun next(): Boolean {
            if (done) return false
            if (left == 0L) {
                var l = line()
                if (l.isEmpty()) l = line()
                left = l.substringBefore(';').trim().toLongOrNull(16) ?: 0
                if (left == 0L) {
                    while (line().isNotEmpty()) { /* trailers */ }
                    done = true
                    return false
                }
            }
            return true
        }
        override fun read(): Int {
            if (!next()) return -1
            val r = inner.read()
            if (r >= 0) left--
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!next()) return -1
            val r = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
    }

    private fun respond(s: Socket, code: Int, body: JSONObject) {
        val b = body.toString().toByteArray()
        val status = when (code) { 200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray()); out.write(b); out.flush()
    }

    private fun handle(s: Socket) {
        s.soTimeout = 120_000
        val ins = BufferedInputStream(s.getInputStream(), 64 * 1024)
        val head = readHead(ins) ?: return
        val lines = head.split("\r\n")
        val req = lines[0].split(" ")
        val method = req.getOrElse(0) { "" }.uppercase()
        val path = req.getOrElse(1) { "/" }.substringBefore('?')
        val h = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val chunked = h["transfer-encoding"]?.contains("chunked") == true
        val len = h["content-length"]?.toLongOrNull()
        val body: InputStream = if (chunked) Chunked(ins) else Limited(ins, len ?: 0)
        val remoteIp = ((s.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "").removePrefix("::ffff:")
        fun hd(k: String) = h[k] ?: ""

        when {
            method == "GET" && path == "/info" -> respond(s, 200, info())
            method == "POST" && path == "/pair" -> {
                val v = try { JSONObject(String(body.readBytes(), Charsets.UTF_8)) } catch (e: Exception) { JSONObject() }
                if (Crypto.norm(v.optString("key")).isEmpty()) return respond(s, 400, JSONObject().put("ok", false))
                v.put("addr", "$remoteIp:${v.optInt("port", Core.TCP_PORT)}")
                Core.pairReq = v
                Alert.pair(Core.app, v.optString("name", "A device"))
                Core.changed()
                respond(s, 200, JSONObject().put("ok", true).put("pending", true))
            }
            method == "POST" && path == "/push" -> {
                val k = Core.keys()
                if (k.token.isEmpty() || hd("x-dp-token") != k.token) {
                    return respond(s, 401, JSONObject().put("ok", false).put("error", "sync key mismatch"))
                }
                val fromId = hd("x-dp-from")
                val fromName = dec(hd("x-dp-name"))
                val port = hd("x-dp-port").toIntOrNull() ?: Core.TCP_PORT
                Core.upsertPeer(fromId, fromName, hd("x-dp-plat"), "$remoteIp:$port", true, 0)
                val inc = Incoming(fromId, fromName, hd("x-dp-batch"), hd("x-dp-index").toIntOrNull() ?: 0,
                    hd("x-dp-count").toIntOrNull() ?: 1, "LAN")
                if (hd("x-dp-kind") == "text") {
                    Core.receiveText(inc, String(body.readBytes(), Charsets.UTF_8))
                    respond(s, 200, JSONObject().put("ok", true))
                } else {
                    val err = Core.receiveFile(inc, dec(hd("x-dp-file")), body, if (chunked) null else len)
                    if (err == null) respond(s, 200, JSONObject().put("ok", true))
                    else respond(s, 507, JSONObject().put("ok", false).put("error", err))
                }
            }
            else -> respond(s, 404, JSONObject().put("ok", false))
        }
    }

    // ---------------- direct push ----------------

    sealed class Result {
        object Ok : Result()
        class Unreachable(val msg: String) : Result()
        class Rejected(val msg: String) : Result()
    }

    fun sendItem(addr: String, it: OutItem, meta: Meta, base: Long): Result {
        val input = try { it.open(Core.app) } catch (e: Exception) { return Result.Rejected("cannot read file: ${e.message}") }
        val c = try { URL("http://$addr/push").openConnection() as HttpURLConnection } catch (e: Exception) {
            input.close(); return Result.Unreachable(e.message ?: "bad address")
        }
        try {
            c.connectTimeout = 2500
            c.readTimeout = 120_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.useCaches = false
            c.setFixedLengthStreamingMode(it.size)
            c.setRequestProperty("X-DP-Token", Core.keys().token)
            c.setRequestProperty("X-DP-From", Core.deviceId)
            c.setRequestProperty("X-DP-Name", enc(Core.deviceName))
            c.setRequestProperty("X-DP-Plat", Core.PLATFORM)
            c.setRequestProperty("X-DP-Port", Core.httpPort.toString())
            c.setRequestProperty("X-DP-Kind", it.kind)
            c.setRequestProperty("X-DP-File", enc(it.name))
            c.setRequestProperty("X-DP-Batch", meta.batch)
            c.setRequestProperty("X-DP-Index", meta.idx.toString())
            c.setRequestProperty("X-DP-Count", meta.count.toString())
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { out ->
                input.use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var n = 0L
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                        Core.setDone(base + n)
                    }
                }
            }
            val code = c.responseCode
            return when (code) {
                200 -> Result.Ok
                401 -> Result.Rejected("the sync key on the other device is different")
                else -> {
                    val msg = try { JSONObject(c.errorStream.bufferedReader().readText()).optString("error") } catch (e: Exception) { "" }
                    Result.Rejected("rejected ($code): $msg")
                }
            }
        } catch (e: Exception) {
            return Result.Unreachable(e.message ?: "unreachable")
        } finally {
            try { input.close() } catch (_: Exception) {}
            c.disconnect()
        }
    }

    fun sendPair(addr: String): String? = try {
        val c = URL("http://$addr/pair").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 5000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("id", Core.deviceId).put("name", Core.deviceName).put("plat", Core.PLATFORM)
            .put("key", Core.key).put("port", Core.httpPort).toString().toByteArray()
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        c.disconnect()
        if (code == 200) null else "rejected ($code)"
    } catch (e: Exception) {
        e.message ?: "failed"
    }

    // ---------------- discovery ----------------

    private fun packet(reply: Boolean): ByteArray = info().put("reply", reply).toString().toByteArray()

    private fun broadcastTargets(): List<InetAddress> {
        val v = ArrayList<InetAddress>()
        v.add(InetAddress.getByName("255.255.255.255"))
        for ((ip, b) in localIfaces()) {
            val bc = b ?: run {
                val o = ip.address
                InetAddress.getByAddress(byteArrayOf(o[0], o[1], o[2], 255.toByte()))
            }
            if (bc !in v) v.add(bc)
        }
        return v
    }

    private fun startUdp() {
        val sock = try {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(Core.UDP_PORT)) }
        } catch (e: Exception) {
            try { DatagramSocket().apply { broadcast = true } } catch (e2: Exception) { return }
        }
        thread(name = "dp-announce") {
            while (true) {
                try {
                    val p = packet(false)
                    for (t in broadcastTargets()) {
                        try { sock.send(DatagramPacket(p, p.size, t, Core.UDP_PORT)) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                }
                Thread.sleep(4000)
            }
        }
        thread(name = "dp-discover") {
            val buf = ByteArray(4096)
            while (true) {
                val dp = DatagramPacket(buf, buf.size)
                try { sock.receive(dp) } catch (e: Exception) { Thread.sleep(200); continue }
                try {
                    val v = JSONObject(String(dp.data, 0, dp.length, Charsets.UTF_8))
                    if (v.optString("app") != "datapush") continue
                    val id = v.optString("id")
                    if (id.isEmpty() || id == Core.deviceId) continue
                    val name = v.optString("name")
                    val group = v.optString("group")
                    val addr = "${dp.address.hostAddress}:${v.optInt("port", Core.TCP_PORT)}"
                    val mine = Core.keys().group
                    if (mine.isNotEmpty() && group == mine) {
                        val was = Core.peerLanOnline(id)
                        Core.upsertPeer(id, name, v.optString("plat"), addr, true, 0)
                        if (!was && !v.optBoolean("reply")) {
                            val p = packet(true)
                            try { sock.send(DatagramPacket(p, p.size, dp.address, dp.port)) } catch (_: Exception) {}
                        }
                    } else {
                        Core.noteForeign(id, name, addr, group.isNotEmpty())
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Probe "ip" or "ip:port". Returns null on success (paired device added) or an error message. */
    fun probe(input: String, timeoutMs: Int): String? {
        val addr = if (input.contains(':')) input else "$input:${Core.TCP_PORT}"
        return try {
            val c = URL("http://$addr/info").openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs + 2000
            val v = JSONObject(c.inputStream.bufferedReader().readText())
            c.disconnect()
            if (v.optString("app") != "datapush") return "$addr is not Random Push"
            val name = v.optString("name")
            val a = "${addr.substringBefore(':')}:${v.optInt("port", Core.TCP_PORT)}"
            val mine = Core.keys().group
            if (mine.isNotEmpty() && v.optString("group") == mine) {
                Core.upsertPeer(v.optString("id"), name, v.optString("plat"), a, true, 0)
                null
            } else {
                Core.noteForeign(v.optString("id"), name, a, v.optString("group").isNotEmpty())
                "Found \"$name\" but it is not paired (different sync key) — use Pair"
            }
        } catch (e: Exception) {
            "No Random Push at $addr"
        }
    }

    fun scan() {
        if (Core.scanning) return
        Core.scanning = true
        Core.changed()
        thread {
            val mine = localIps()
            val hosts = ArrayList<String>()
            for (ip in mine) {
                val pre = ip.substringBeforeLast('.')
                for (h in 1..254) {
                    val s = "$pre.$h"
                    if (s !in mine && s !in hosts) hosts.add(s)
                }
            }
            val workers = (0 until 32).map {
                thread {
                    while (true) {
                        val h = synchronized(hosts) { if (hosts.isEmpty()) null else hosts.removeAt(hosts.size - 1) } ?: break
                        probe(h, 600)
                    }
                }
            }
            workers.forEach { it.join() }
            Core.scanning = false
            Core.changed()
        }
    }
}
