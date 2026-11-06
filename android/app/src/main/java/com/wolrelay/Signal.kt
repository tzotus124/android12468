package com.wolrelay

import java.net.*
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Crypto {
    fun sha(vararg p: ByteArray) = MessageDigest.getInstance("SHA-256").digest(p.fold(ByteArray(0)) { a, b -> a + b })
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun sigKey(room: ByteArray) = sha(room, "key".toByteArray())

    fun fingerprint(room: String, phoneIp: String, pcIp: String): String {
        val h = hex(sha("$room|$phoneIp|$pcIp".toByteArray())).uppercase()
        return "${h.substring(0, 4)}-${h.substring(4, 8)}-${h.substring(8, 12)}"
    }

    fun hmacHex(key: ByteArray, msg: String): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256")); return hex(m.doFinal(msg.toByteArray()))
    }
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
