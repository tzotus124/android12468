package com.relay.wol
import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import java.io.File
import java.net.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val PORT = 48999
fun prefs(c: Context) = c.getSharedPreferences("wol", 0)
fun token(c: Context): String = prefs(c).getString("token", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").take(24).also { prefs(c).edit().putString("token", it).apply() }
fun tailscaleIp(): String? = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
    .filterIsInstance<Inet4Address>().map { it.hostAddress!! }.firstOrNull { val p = it.split("."); p[0] == "100" && p[1].toInt() in 64..127 }

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { if (prefs(c).getBoolean("on", true)) c.startForegroundService(Intent(c, RelayService::class.java)) }
}

fun hmac(key: String, msg: String): String = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
    .doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }

class RelayService : Service() {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var wl: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?) = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val ch = "relay"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(ch, "Relay", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, ch).setContentTitle("WoL Relay active").setContentText("Listening on port $PORT")
            .setSmallIcon(android.R.drawable.ic_lock_power_off).setOngoing(true).build())
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wol:relay").apply { acquire() }
        if (!running) { running = true; Thread { loop() }.start() }
        return START_STICKY
    }
    override fun onDestroy() { running = false; server?.close(); wl?.release() }

    private fun loop() {
        while (running) try {
            server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
            while (running) { val s = server!!.accept(); Thread { handle(s) }.start() }
        } catch (e: Exception) { Thread.sleep(3000) }
    }

    private fun handle(s: Socket) = s.use {
        val out = s.getOutputStream()
        fun reply(t: String) { out.write((t + "\n").toByteArray()); out.flush() }
        try {
            // Auth = Tailscale: only peers inside the tailnet (100.64.0.0/10) are accepted
            val src = (s.remoteSocketAddress as InetSocketAddress).address.hostAddress ?: ""
            val p = src.split(".")
            if (p.size != 4 || p[0] != "100" || p[1].toIntOrNull() !in 64..127) return reply("DENIED")
            s.soTimeout = 8000
            val j = org.json.JSONObject(s.getInputStream().bufferedReader().readLine())
            val mac = j.optString("mac"); val ip = j.optString("ip")
            // Token check: request must be signed with a secret from a PC token generated on this phone
            val t = j.optString("ts"); val msg = "$t|$mac|$ip"
            val secrets = prefs(this).getStringSet("secrets", emptySet()) ?: emptySet()
            val fresh = Math.abs(System.currentTimeMillis() / 1000 - (t.toLongOrNull() ?: 0L)) <= 60
            if (!fresh || secrets.none { hmac(it, msg) == j.optString("sig") }) return reply("DENIED (invalid token)")
            val m = mac.ifEmpty { arpLookup(ip) } ?: return reply("NEED_MAC (IP not in ARP cache, use MAC)")
            sendMagic(m, ip.ifEmpty { null }); reply("OK")
        } catch (e: Exception) { runCatching { reply("ERROR ${e.message}") } }
    }

    private fun arpLookup(ip: String): String? = runCatching {
        File("/proc/net/arp").readLines().drop(1).map { it.split(Regex("\\s+")) }
            .firstOrNull { it[0] == ip && it[3] != "00:00:00:00:00:00" }?.get(3)
    }.getOrNull()

    private fun sendMagic(mac: String, ip: String?) {
        val b = mac.split(":", "-").map { it.toInt(16).toByte() }.toByteArray()
        val data = ByteArray(6) { 0xFF.toByte() } + ByteArray(96).also { for (i in 0 until 16) b.copyInto(it, i * 6) }
        val targets = mutableListOf("255.255.255.255")
        NetworkInterface.getNetworkInterfaces().toList().forEach { n -> n.interfaceAddresses.forEach { a -> a.broadcast?.hostAddress?.let { targets += it } } }
        ip?.let { targets += it }
        DatagramSocket().use { d -> d.broadcast = true
            for (t in targets.distinct()) for (p in intArrayOf(9, 7)) runCatching { d.send(DatagramPacket(data, data.size, InetAddress.getByName(t), p)) } }
    }
}
