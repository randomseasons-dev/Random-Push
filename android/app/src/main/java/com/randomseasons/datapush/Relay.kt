package com.randomseasons.datapush

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Internet relay through an ntfy server — same envelope format as the Windows app (src-tauri/src/relay.rs). */
object Relay {
    private fun server(): String {
        var v = Core.relayServer.trim().trimEnd('/')
        if (v.isEmpty()) v = "https://ntfy.sh"
        if (!v.startsWith("http://") && !v.startsWith("https://")) v = "https://$v"
        return v
    }

    private fun setStatus(s: String) {
        if (Core.relayStatus != s) {
            Core.relayStatus = s
            Core.changed()
        }
    }

    fun start() {
        thread(name = "dp-relay") { subscribeLoop() }
        // presence: "hello" on start, on key change and every 10 minutes
        thread(name = "dp-hello") {
            var last = 0L
            var lastGen = -1L
            while (true) {
                Thread.sleep(5000)
                val gen = Core.relayGen.get()
                if (gen != lastGen || Core.now() - last >= 600_000) {
                    val env = JSONObject().put("t", "dp1").put("k", "hello").put("id", Crypto.randomHex(8))
                        .put("from", Core.deviceId).put("name", Core.deviceName).put("plat", Core.PLATFORM)
                    if (publish(env, null) == null || !Core.relayEnabled) {
                        last = Core.now(); lastGen = gen
                    }
                }
            }
        }
    }

    private fun subscribeLoop() {
        var backoff = 5
        while (true) {
            val gen = Core.relayGen.get()
            val k = Core.keys()
            if (!Core.relayEnabled || k.topic.isEmpty()) {
                setStatus(if (Core.relayEnabled) "off (no sync key)" else "off")
                Thread.sleep(3000)
                continue
            }
            val since = if (Core.relayLastTime == 0L) "all" else Core.relayLastTime.toString()
            try {
                val c = URL("${server()}/${k.topic}/json?since=$since").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 100_000
                c.inputStream.bufferedReader().use { r ->
                    setStatus("connected (${server().substringAfter("://")})")
                    backoff = 5
                    while (true) {
                        val line = r.readLine() ?: break
                        handleLine(k, line)
                        if (Core.relayGen.get() != gen) break
                    }
                }
                c.disconnect()
                setStatus("reconnecting…")
            } catch (e: Exception) {
                setStatus("offline (${(e.message ?: e.javaClass.simpleName).take(60)})")
                backoff = minOf(backoff * 2, 60)
            }
            for (i in 0 until backoff) {
                if (Core.relayGen.get() != gen) break
                Thread.sleep(1000)
            }
        }
    }

    private fun download(url: String): ByteArray? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 120_000
        val b = c.inputStream.use { it.readBytes() }
        c.disconnect()
        b
    } catch (e: Exception) {
        null
    }

    private fun handleLine(k: Keys, line: String) {
        val v = try { JSONObject(line) } catch (e: Exception) { return }
        if (v.optString("event") != "message") return
        val id = v.optString("id")
        val time = v.optLong("time")
        synchronized(Core) {
            if (Core.relayRecent.contains(id)) return
            Core.relayRecent.add(id)
            while (Core.relayRecent.size > 200) Core.relayRecent.removeAt(0)
            if (time > Core.relayLastTime) Core.relayLastTime = time
        }
        Core.saveSettings()
        val env = Crypto.b64d(v.optString("message"))?.let { Crypto.decrypt(k.enc, it) }
            ?.let { try { JSONObject(String(it, Charsets.UTF_8)) } catch (e: Exception) { null } } ?: return
        if (env.optString("t") != "dp1") return
        val from = env.optString("from")
        if (from.isEmpty() || from == Core.deviceId) return
        val name = env.optString("name")
        Core.upsertPeer(from, name, env.optString("plat"), null, false, time * 1000)
        if (env.optString("k") != "item" || env.optString("to") != Core.deviceId) return
        val inc = Incoming(from, name, env.optString("batch"), env.optInt("idx"), env.optInt("count", 1), "internet")
        val attachment = {
            v.optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotEmpty() }
                ?.let { download(it) }?.let { Crypto.decrypt(k.enc, it) }
        }
        if (env.optString("kind") == "text") {
            val text = if (env.has("text")) env.optString("text") else attachment()?.let { String(it, Charsets.UTF_8) } ?: return
            Core.receiveText(inc, text)
        } else {
            val data = attachment() ?: return
            Core.receiveFile(inc, env.optString("file", "file"), data.inputStream(), data.size.toLong())
        }
    }

    /** Returns an error message or null. */
    fun publish(env: JSONObject, payload: ByteArray?): String? {
        if (!Core.relayEnabled) return "internet relay is off"
        val k = Core.keys()
        if (k.topic.isEmpty()) return "no sync key"
        val msg = Crypto.b64e(Crypto.encrypt(k.enc, env.toString().toByteArray(Charsets.UTF_8)))
        return try {
            val c = URL("${server()}/${k.topic}").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 300_000; c.doOutput = true
            val body: ByteArray
            if (payload == null) {
                c.requestMethod = "POST"
                body = msg.toByteArray()
            } else {
                c.requestMethod = "PUT"
                c.setRequestProperty("X-Message", msg)
                c.setRequestProperty("X-Filename", "dp.bin")
                body = Crypto.encrypt(k.enc, payload)
            }
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val err = if (code in 200..299) null else {
                val t = try { c.errorStream?.bufferedReader()?.readText() ?: "" } catch (e: Exception) { "" }
                "relay refused ($code) ${t.take(60)}"
            }
            c.disconnect()
            err
        } catch (e: Exception) {
            "relay unreachable (${(e.message ?: "").take(60)})"
        }
    }

    /** Returns an error message or null. */
    fun sendItem(to: String, it: OutItem, meta: Meta): String? {
        val env = JSONObject().put("t", "dp1").put("k", "item").put("id", Crypto.randomHex(8))
            .put("from", Core.deviceId).put("name", Core.deviceName).put("plat", Core.PLATFORM).put("to", to)
            .put("kind", it.kind).put("file", it.name).put("size", it.size)
            .put("batch", meta.batch).put("idx", meta.idx).put("count", meta.count)
        if (it.kind == "text") {
            val bytes = it.text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= 2000) return publish(env.put("text", it.text), null)
            return publish(env, bytes)
        }
        if (it.size + 28 > Core.relayMaxMb * 1024 * 1024) {
            return "too big for the internet relay (limit ${Core.relayMaxMb} MB) — put both devices on the same network"
        }
        val data = try {
            it.open(Core.app).use { s -> ByteArrayOutputStream().also { o -> s.copyTo(o) }.toByteArray() }
        } catch (e: Exception) {
            return "cannot read file: ${e.message}"
        }
        return publish(env, data)
    }
}
