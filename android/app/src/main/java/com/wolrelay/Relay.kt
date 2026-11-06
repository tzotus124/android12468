package com.wolrelay

import android.content.Context
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

data class UiState(
    val paired: Boolean = false, val token: String? = null, val tokenExpires: Long = 0,
    val status: String = "Idle", val tunnelUp: Boolean = false, val fingerprint: String = "",
    val lastWake: String = "No wakes yet", val running: Boolean = false, val log: List<String> = emptyList(),
)

object Relay {
    private const val UDP_PORT = 51899
    private const val WG_PORT = 51820
    private const val CMD_PORT = 9009
    private const val ANDROID_IP = "10.77.0.1"
    private const val PC_IP = "10.77.0.2"

    val state = MutableStateFlow(UiState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var pairJob: Job? = null
    private lateinit var app: Context
    private val backend by lazy { GoBackend(app) }
    private val tunnel = object : Tunnel {
        override fun getName() = "wolrelay"
        override fun onStateChange(newState: Tunnel.State) { state.update { it.copy(tunnelUp = newState == Tunnel.State.UP) } }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext; Prefs.init(app)
        state.update { it.copy(paired = Prefs.paired, fingerprint = fp()) }
    }

    private fun fp(): String {
        if (!Prefs.paired) return ""
        val h = MessageDigest.getInstance("SHA-256").digest(listOf(Prefs.pub, Prefs.pcPub).sorted().joinToString("").toByteArray())
            .joinToString("") { "%02X".format(it) }
        return "${h.substring(0, 4)}-${h.substring(4, 8)}-${h.substring(8, 12)}"
    }

    private fun log(msg: String, status: String? = null) = state.update {
        it.copy(status = status ?: it.status, log = (listOf(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg) + it.log).take(40))
    }

    private fun http(method: String, url: String, body: JSONObject? = null, readMs: Int = 10000): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = readMs
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code >= 400) error(runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP $code"))
        return JSONObject(text)
    }

    // ---------- pairing ----------
    fun startPairing(server: String) {
        pairJob?.cancel()
        pairJob = scope.launch {
            try {
                val url = server.trim().trimEnd('/'); Prefs.server = url
                val kp = KeyPair()
                val r = http("POST", "$url/pair/create", JSONObject().put("pub", kp.publicKey.toBase64()))
                val room = r.getString("room"); val exp = System.currentTimeMillis() + r.getInt("expires") * 1000L
                state.update { it.copy(token = r.getString("token"), tokenExpires = exp, status = "Waiting for the PC…") }
                var since = 0
                while (System.currentTimeMillis() < exp) {
                    val w = http("GET", "$url/room/$room?role=android&since=$since", null, 35000)
                    since = w.getInt("ver")
                    if (!w.isNull("peer_pub")) {
                        Prefs.room = room; Prefs.priv = kp.privateKey.toBase64(); Prefs.pub = kp.publicKey.toBase64(); Prefs.pcPub = w.getString("peer_pub")
                        state.update { it.copy(paired = true, token = null, fingerprint = fp(), status = "Linked") }
                        log("Linked with a PC"); return@launch
                    }
                }
                state.update { it.copy(token = null, status = "Token expired") }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { state.update { it.copy(token = null) }; log("Pairing failed: ${e.message}", "Error") }
        }
    }

    fun unpair() {
        pairJob?.cancel()
        val (s, r) = Prefs.server to Prefs.room
        scope.launch { runCatching { http("DELETE", "$s/room/$r") } }
        Prefs.clearPairing(); state.update { it.copy(paired = false, token = null, fingerprint = "", status = "Idle") }
    }

    // ---------- always-on loop (runs inside the foreground service) ----------
    fun start() {
        if (loopJob?.isActive == true) return
        state.update { it.copy(running = true, status = "Listening") }
        loopJob = scope.launch {
            var since = 0; var handled = 0.0
            while (isActive) {
                if (!Prefs.paired) { delay(2000); continue }
                try {
                    val r = http("GET", "${Prefs.server}/room/${Prefs.room}?role=android&since=$since", null, 35000)
                    since = r.getInt("ver")
                    val st = r.getJSONObject("stamps"); val pc = st.optDouble("pc", 0.0); val an = st.optDouble("android", 0.0)
                    if (pc > handled && pc > an && !r.isNull("peer_ep")) { handled = pc; session(r.getString("peer_ep")) }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { log("Server: ${e.message}", "Reconnecting…"); delay(5000) }
            }
        }
    }

    fun stop() { loopJob?.cancel(); runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }; state.update { it.copy(running = false, status = "Stopped", tunnelUp = false) } }

    private fun discover() {
        DatagramSocket(null).use { s ->
            s.reuseAddress = true; s.bind(InetSocketAddress(WG_PORT)); s.soTimeout = 2500
            val d = "android:${Prefs.room}".toByteArray(); val host = InetAddress.getByName(URL(Prefs.server).host)
            repeat(3) {
                s.send(DatagramPacket(d, d.size, host, UDP_PORT))
                try { s.receive(DatagramPacket(ByteArray(64), 64)); return } catch (_: SocketTimeoutException) {}
            }
            error("Rendezvous UDP port unreachable")
        }
    }

    private fun tunnelUp(pcEndpoint: String) {
        val cfg = Config.Builder()
            .setInterface(Interface.Builder().addAddress(InetNetwork.parse("$ANDROID_IP/24")).parsePrivateKey(Prefs.priv).setListenPort(WG_PORT).build())
            .addPeer(Peer.Builder().parsePublicKey(Prefs.pcPub).addAllowedIp(InetNetwork.parse("$PC_IP/32"))
                .setEndpoint(InetEndpoint.parse(pcEndpoint)).setPersistentKeepalive(15).build())
            .build()
        backend.setState(tunnel, Tunnel.State.UP, cfg)
    }

    private suspend fun session(pcEndpoint: String) {
        log("PC is calling", "Opening tunnel…")
        try {
            discover(); tunnelUp(pcEndpoint)
            log("Tunnel up", "Connected")
            serve()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { log("Session failed: ${e.message}", "Error") }
        finally { runCatching { backend.setState(tunnel, Tunnel.State.DOWN, null) }; state.update { it.copy(tunnelUp = false, status = "Listening") } }
    }

    /** Accepts commands only from the paired PC's tunnel address; ends after 90 s idle or 10 min total. */
    private suspend fun serve() = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis(); var last = started
        ServerSocket(CMD_PORT).use { ss ->
            ss.soTimeout = 3000
            while (isActive && System.currentTimeMillis() - last < 90_000 && System.currentTimeMillis() - started < 600_000) {
                val c = try { ss.accept() } catch (_: SocketTimeoutException) { continue }
                c.use {
                    if (it.inetAddress.hostAddress != PC_IP) return@use
                    last = System.currentTimeMillis()
                    it.soTimeout = 5000
                    val reply = try { handle(it.getInputStream().bufferedReader().readLine()) }
                    catch (e: Exception) { JSONObject().put("ok", false).put("err", e.message ?: "error") }
                    it.getOutputStream().write((reply.toString() + "\n").toByteArray())
                }
            }
        }
    }

    private fun handle(line: String): JSONObject {
        fun fail(e: String) = JSONObject().put("ok", false).put("err", e)
        val j = JSONObject(line)
        if (j.optString("cmd") != "wake") return fail("bad_cmd")
        var mac = j.optString("mac"); val ip = j.optString("ip")
        if (mac.isEmpty() && ip.isNotEmpty()) mac = WakeOnLan.arpLookup(ip) ?: return fail("unknown_mac")
        if (!Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$").matches(mac)) return fail("bad_mac")
        WakeOnLan.send(app, mac)
        val now = SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date())
        state.update { it.copy(lastWake = "$mac · $now") }; log("Wake sent to $mac")
        return JSONObject().put("ok", true)
    }
}
