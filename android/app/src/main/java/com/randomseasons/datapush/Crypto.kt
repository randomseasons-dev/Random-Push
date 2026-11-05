package com.randomseasons.datapush

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Same derivations as the Windows app (src-tauri/src/engine.rs `keys`).
class Keys(key: String) {
    private val n = Crypto.norm(key)
    val group = if (n.isEmpty()) "" else Crypto.hex(Crypto.sha("datapush-group:$n")).substring(0, 16)
    val token = if (n.isEmpty()) "" else Crypto.hex(Crypto.sha("datapush-auth:$n"))
    val topic = if (n.isEmpty()) "" else "dp_" + Crypto.hex(Crypto.sha("datapush-topic:$n")).substring(0, 32)
    val enc: ByteArray = Crypto.sha("datapush-enc:$n")
}

object Crypto {
    private val rnd = SecureRandom()

    fun norm(k: String) = k.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }.uppercase()

    fun sha(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun randomHex(n: Int) = hex(ByteArray(n).also { rnd.nextBytes(it) })

    fun newKey(): String {
        val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (0 until 16).joinToString("") { i -> (if (i > 0 && i % 4 == 0) "-" else "") + a[rnd.nextInt(a.length)] }
    }

    /** nonce(12) || ciphertext || tag(16) */
    fun encrypt(key: ByteArray, data: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce + c.doFinal(data)
    }

    fun decrypt(key: ByteArray, data: ByteArray): ByteArray? = try {
        if (data.size < 28) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
            c.doFinal(data, 12, data.size - 12)
        }
    } catch (e: Exception) {
        null
    }

    fun b64e(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    fun b64d(s: String): ByteArray? = try {
        Base64.decode(s.trim(), Base64.DEFAULT)
    } catch (e: Exception) {
        null
    }
}
