package com.wolrelay

import android.util.Base64
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class Msg(val id: String, val time: Long, val text: String)

/** Free public pub/sub (ntfy.sh) used only as a mailbox for ENCRYPTED hello messages. Nothing to configure. */
object Ntfy {
    private const val BASE = "https://ntfy.sh"

    fun publish(topic: String, text: String) {
        val c = URL("$BASE/$topic").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 8000
        c.outputStream.use { it.write(text.toByteArray()) }
        if (c.responseCode >= 300) error("Signalling HTTP ${c.responseCode}")
        c.inputStream.close()
    }

    /** Blocking. Calls onMsg for each new message; returns when onMsg returns true, active() is false or deadline passes. */
    fun listen(topic: String, since: Long, readMs: Int, deadline: Long = Long.MAX_VALUE, active: () -> Boolean, onMsg: (Msg) -> Boolean) {
        val seen = HashSet<String>(); var from = since
        while (active() && System.currentTimeMillis() < deadline) {
            try {
                val c = URL("$BASE/$topic/json?since=$from").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = readMs
                c.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        val j = JSONObject(line)
                        if (j.optString("event") != "message" || !seen.add(j.getString("id"))) continue
                        from = maxOf(from, j.getLong("time"))
                        if (onMsg(Msg(j.getString("id"), j.getLong("time"), j.getString("message")))) return
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Thread.sleep(2000) }
        }
    }
}

object Crypto {
    private val rnd = SecureRandom()
    fun sha(vararg p: ByteArray) = MessageDigest.getInstance("SHA-256").digest(p.fold(ByteArray(0)) { a, b -> a + b })
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun pairKey(token: String): ByteArray = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(token.toCharArray(), "wolrelay-pair-v1".toByteArray(), 200_000, 256)).encoded
    fun pairTopic(token: String) = "wolp" + hex(sha("wol-pair:".toByteArray(), token.toByteArray())).take(40)
        fun sigKey(room: ByteArray) = sha(room, "key".toByteArray())

    fun fingerprint(room: String, phoneIp: String, pcIp: String): String {
        val h = hex(sha("$room|$phoneIp|$pcIp".toByteArray())).uppercase()
        return "${h.substring(0, 4)}-${h.substring(4, 8)}-${h.substring(8, 12)}"
    }

    fun hmacHex(key: ByteArray, msg: String): String {
        val m = javax.crypto.Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256")); return hex(m.doFinal(msg.toByteArray()))
    }

    fun seal(key: ByteArray, plain: String): String {
        val n = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, n))
        return Base64.encodeToString(n + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun open(key: ByteArray, text: String): JSONObject? = try {
        val raw = Base64.decode(text.trim(), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        JSONObject(String(c.doFinal(raw, 12, raw.size - 12)))
    } catch (e: Exception) { null }
}

/** Finds this phone's Tailscale address (100.64.0.0/10). Present only while the phone is signed in to Tailscale and connected. */
object Tailnet {
    fun isTailnet(a: InetAddress): Boolean {
        val b = a.address; return a is Inet4Address && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40
    }
    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { isTailnet(it) }?.hostAddress
    } catch (_: Exception) { null }
}
