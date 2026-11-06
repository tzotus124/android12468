package com.wolrelay

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

data class UiState(
    val paired: Boolean = false, val token: String? = null, val tokenExpires: Long = 0, val pendingFp: String? = null,
    val status: String = "Idle", val tailnetIp: String? = null, val fingerprint: String = "",
    val lastWake: String = "No wakes yet", val running: Boolean = false, val log: List<String> = emptyList(),
)

/**
 * Networking is done by Tailscale (the official Tailscale app on this phone, which requires sign-in).
 * This object only (1) pairs with the PC and hands it a Tailscale pre-auth key so the PC never needs a login,
 * and (2) listens on port 9009 for signed wake commands arriving over the tailnet.
 */
object Relay {
    private const val CMD_PORT = 9009

    val state = MutableStateFlow(UiState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var pairJob: Job? = null
    private var confirmation: CompletableDeferred<Boolean>? = null
    private lateinit var app: Context
    private fun fp() = if (Prefs.paired) Crypto.fingerprint(Prefs.room, Prefs.phoneIp, Prefs.pcIp) else ""

    fun init(ctx: Context) {
        app = ctx.applicationContext; Prefs.init(app)
        if (Prefs.room.isNotEmpty() && Prefs.pcIp.isEmpty() && state.value.token == null) Prefs.clearPairing()  // unfinished token from a previous run
        state.update { it.copy(paired = Prefs.paired, fingerprint = fp(), tailnetIp = Tailnet.localIp()) }
    }

    fun refreshTailnet() { val ip = Tailnet.localIp(); if (ip != state.value.tailnetIp) state.update { it.copy(tailnetIp = ip) } }

    private fun log(msg: String, status: String? = null) = state.update {
        it.copy(status = status ?: it.status, log = (listOf(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg) + it.log).take(40))
    }

    // ---------- linking: the phone shows one link token; the PC pastes it ----------
    /**
     * Creates a single-use, 10-minute Tailscale auth key via the API and packs it with this phone's tailnet IP and a fresh
     * shared secret into one link token ("wr1." + base64url JSON). Nothing goes through any third-party server.
     */
    fun generateToken() {
        pairJob?.cancel()
        pairJob = scope.launch {
            try {
                val phoneIp = Tailnet.localIp() ?: run { log("Sign in to Tailscale first", "Tailscale needed"); return@launch }
                if (Prefs.tsCred.isEmpty()) { log("Add your Tailscale API credential first", "Setup needed"); return@launch }
                state.update { it.copy(status = "Creating Tailscale key…") }
                val authKey = try { TailscaleApi.createAuthKey(Prefs.tsCred, Prefs.tsTag) }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { log("Key creation failed: ${e.message}", "Error"); return@launch }
                val room = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
                val token = "wr1." + Base64.encodeToString(JSONObject().put("k", authKey).put("ip", phoneIp).put("r", room).toString().toByteArray(),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                Prefs.room = room; Prefs.phoneIp = phoneIp; Prefs.pcIp = ""
                state.update { it.copy(token = token, tokenExpires = System.currentTimeMillis() + 600_000, status = "Waiting for the PC…") }
                log("Link token ready")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { log("Failed: ${e.message}", "Error") }
        }
    }

    /** Cancels a waiting token or unlinks the current PC. */
    fun unpair() {
        pairJob?.cancel(); Prefs.clearPairing()
        state.update { it.copy(paired = false, token = null, fingerprint = "", status = "Idle") }
    }

    // ---------- always-on listener (runs inside the foreground service) ----------
    fun start() {
        if (loopJob?.isActive == true) return
        state.update { it.copy(running = true, status = "Listening") }
        loopJob = scope.launch {
            while (isActive) {
                if (Prefs.room.isEmpty()) { delay(2000); continue }
                try {
                    ServerSocket(CMD_PORT).use { ss ->
                        ss.soTimeout = 3000
                        while (isActive && Prefs.room.isNotEmpty()) {
                            val c = try { ss.accept() } catch (_: SocketTimeoutException) { continue }
                            c.use {
                                // Only tailnet addresses (the paired PC once known), and only correctly signed commands.
                                val src = it.inetAddress
                                if (!Tailnet.isTailnet(src) || (Prefs.pcIp.isNotEmpty() && src.hostAddress != Prefs.pcIp)) return@use
                                it.soTimeout = 5000
                                val reply = try { handle(it.getInputStream().bufferedReader().readLine(), src.hostAddress!!) }
                                catch (e: Exception) { JSONObject().put("ok", false).put("err", e.message ?: "error") }
                                it.getOutputStream().write((reply.toString() + "\n").toByteArray())
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { log("Listener: ${e.message}", "Reconnecting…"); delay(5000) }
            }
        }
    }

    fun stop() { loopJob?.cancel(); loopJob = null; state.update { it.copy(running = false, status = "Stopped") } }

    private fun handle(line: String, from: String): JSONObject {
        fun fail(e: String) = JSONObject().put("ok", false).put("err", e)
        val j = JSONObject(line)
        val ts = j.optLong("ts"); val cmd = j.optString("cmd"); var mac = j.optString("mac"); val ip = j.optString("ip")
        val expect = Crypto.hmacHex(Crypto.sigKey(Base64.decode(Prefs.room, Base64.NO_WRAP)), "$cmd|$mac|$ip|$ts")
        if (abs(System.currentTimeMillis() / 1000 - ts) > 120 ||
            !MessageDigest.isEqual(expect.toByteArray(), j.optString("sig").toByteArray())) return fail("unauthorized")
        if (Prefs.pcIp.isEmpty()) {  // first valid signed message completes the link and pins the PC's address
            Prefs.pcIp = from
            state.update { it.copy(paired = true, token = null, fingerprint = fp(), status = "Linked") }; log("Linked with PC $from")
        }
        if (cmd == "hello") return JSONObject().put("ok", true)
        if (cmd != "wake") return fail("bad_cmd")
        if (mac.isEmpty() && ip.isNotEmpty()) mac = WakeOnLan.arpLookup(ip) ?: return fail("unknown_mac")
        if (!Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$").matches(mac)) return fail("bad_mac")
        WakeOnLan.send(app, mac)
        val now = SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date())
        state.update { it.copy(lastWake = "$mac · $now") }; log("Wake sent to $mac")
        return JSONObject().put("ok", true)
    }
}
