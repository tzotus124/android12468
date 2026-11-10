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

private val LOG_LOCK = Any()
fun addLog(c: Context, text: String) = synchronized(LOG_LOCK) {
    val stamp = java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
    val old = org.json.JSONArray(prefs(c).getString("log", "[]"))
    val n = org.json.JSONArray().put("$stamp  $text")
    for (i in 0 until minOf(old.length(), 199)) n.put(old.getString(i))
    prefs(c).edit().putString("log", n.toString()).apply()
}
fun loadLog(c: Context): List<String> = runCatching {
    val a = org.json.JSONArray(prefs(c).getString("log", "[]")); (0 until a.length()).map { a.getString(it) }
}.getOrDefault(emptyList())

/** A PC that is (or will be) allowed to wake through this phone. One token = one device: the first PC that uses it is bound to it. */
data class Client(val secret: String, val name: String, val ip: String, val first: Long, val last: Long, val wakes: Int)

private val CLIENT_LOCK = Any()
fun newSecret(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
fun loadClients(c: Context): List<Client> = runCatching {
    val p = prefs(c)
    val a = org.json.JSONArray(p.getString("clients", "[]"))
    val list = (0 until a.length()).map { val o = a.getJSONObject(it)
        Client(o.getString("secret"), o.optString("name"), o.optString("ip"), o.optLong("first"), o.optLong("last"), o.optInt("wakes")) }.toMutableList()
    // migrate tokens made by older versions (unbound)
    val old = p.getStringSet("secrets", null)
    if (old != null) { old.filter { s -> list.none { it.secret == s } }.forEach { list += Client(it, "", "", 0, 0, 0) }
        p.edit().remove("secrets").putString("clients", clientsJson(list)).apply() }
    list.toList()
}.getOrDefault(emptyList())
private fun clientsJson(l: List<Client>): String { val a = org.json.JSONArray()
    l.forEach { a.put(org.json.JSONObject().put("secret", it.secret).put("name", it.name).put("ip", it.ip).put("first", it.first).put("last", it.last).put("wakes", it.wakes)) }
    return a.toString() }
/** Atomic read-modify-write of the client list. */
fun editClients(c: Context, f: (MutableList<Client>) -> Unit) = synchronized(CLIENT_LOCK) {
    val l = loadClients(c).toMutableList(); f(l); prefs(c).edit().putString("clients", clientsJson(l)).apply() }

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
            val act = j.optString("action"); val t = j.optString("ts"); val msg = "$t|$mac|$ip|$act"
            val fresh = Math.abs(System.currentTimeMillis() / 1000 - (t.toLongOrNull() ?: 0L)) <= 60
            val sig = j.optString("sig")
            val devName = j.optString("name").trim().take(40)
            // Each token belongs to ONE device: the first PC that uses it is bound to its Tailscale IP; any other device using it is refused.
            var verdict = "INVALID"
            synchronized(CLIENT_LOCK) {
                val list = loadClients(this@RelayService).toMutableList()
                val i = if (fresh) list.indexOfFirst { java.security.MessageDigest.isEqual(hmac(it.secret, msg).toByteArray(), sig.toByteArray()) } else -1
                if (i >= 0) {
                    val cl = list[i]; val now = System.currentTimeMillis()
                    if (cl.ip.isNotEmpty() && cl.ip != src) verdict = "BOUND"
                    else { verdict = "OK"
                        list[i] = cl.copy(ip = src, name = devName.ifEmpty { cl.name.ifEmpty { src } }, first = if (cl.first == 0L) now else cl.first, last = now)
                        prefs(this@RelayService).edit().putString("clients", clientsJson(list)).apply()
                        if (cl.ip.isEmpty()) addLog(this@RelayService, "＋ Approved new PC ${list[i].name} ($src)") }
                }
            }
            if (verdict == "BOUND") { addLog(this@RelayService, "✖ Rejected $src: token already belongs to another PC"); return reply("DENIED (token already used by another PC)") }
            if (verdict != "OK") { addLog(this@RelayService, "✖ Rejected request from $src (invalid token)"); return reply("DENIED (invalid token)") }
            if (act == "ping") return reply("PONG|${Build.MODEL}")
            val m = mac.ifEmpty { arpLookup(ip) } ?: run { addLog(this@RelayService, "✖ Wake ${ip.ifEmpty { "?" }} from $src failed: MAC unknown"); return reply("NEED_MAC (IP not in ARP cache, use MAC)") }
            sendMagic(m, ip.ifEmpty { null })
            editClients(this@RelayService) { l -> val k = l.indexOfFirst { it.ip == src }; if (k >= 0) l[k] = l[k].copy(wakes = l[k].wakes + 1) }
            addLog(this@RelayService, "✔ Wake $m${if (ip.isNotEmpty()) " ($ip)" else ""} from $src"); reply("OK")
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
