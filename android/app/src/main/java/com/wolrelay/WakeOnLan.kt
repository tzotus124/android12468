package com.wolrelay

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {
    fun magic(mac: String): ByteArray {
        val m = mac.split(":", "-").map { it.toInt(16).toByte() }.toByteArray()
        return ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { m.asList() }.toByteArray()
    }

    @Suppress("DEPRECATION")
    private fun subnetBroadcast(ctx: Context): String? {
        val d = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo ?: return null
        if (d.ipAddress == 0 || d.netmask == 0) return null
        val bc = (d.ipAddress and d.netmask) or d.netmask.inv()
        return InetAddress.getByAddress(ByteArray(4) { (bc shr (8 * it) and 0xFF).toByte() }).hostAddress
    }

    fun send(ctx: Context, mac: String) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val pkt = magic(mac)
        val targets = listOfNotNull("255.255.255.255", subnetBroadcast(ctx))
        DatagramSocket().use { s ->
            s.broadcast = true
            wifi?.bindSocket(s)
            repeat(3) {
                for (t in targets) for (port in intArrayOf(9, 7))
                    s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(t), port))
                Thread.sleep(150)
            }
        }
    }

    /** Best effort: Android 10+ hides the ARP table from apps, so this often returns null. */
    fun arpLookup(ip: String): String? = try {
        File("/proc/net/arp").readLines().drop(1).map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 4 && it[0] == ip && it[3] != "00:00:00:00:00:00" }?.get(3)
    } catch (e: Exception) { null }
}
