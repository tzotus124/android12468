package com.relay.wol
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** One approved PC (a computer running WoL Remote that was linked with a token made on this phone). */
data class Pc(
    val secret: String,        // the token secret that identifies this PC (never shown)
    val name: String,          // name the PC sent (WoL Remote > Relay phones > "This PC's name"), or "PC n" until it connects
    val named: Boolean,        // true once the PC itself has told us its name
    val hasPin: Boolean,       // a wake PIN is set for this PC
    val unlocked: Int,         // how many target MACs this PC has already unlocked with the PIN
    val lastSeen: Long,        // 0 = has not connected yet
    val lastIp: String,
)

sealed class PinResult {
    object Ok : PinResult()
    object Bad : PinResult()
    class Locked(val secs: Int) : PinResult()
}

/**
 * Approved-PC list. Stored as JSON in prefs "pcs": { secret: {name, named, created, lastSeen, lastIp, salt, hash, macs[]} }.
 * The old "secrets" string set stays the source of truth for who is approved (so older versions of the app keep working);
 * entries here are created / dropped to match it.
 */
object Pcs {
    private val lock = Any()
    private val fails = HashMap<String, Pair<Int, Long>>()      // secret -> (wrong PINs in a row, locked until)
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 60_000L
    private val rnd = SecureRandom()

    private fun read(c: Context): JSONObject = runCatching { JSONObject(prefs(c).getString("pcs", "{}")!!) }.getOrDefault(JSONObject())
    private fun write(c: Context, o: JSONObject) { prefs(c).edit().putString("pcs", o.toString()).apply() }
    private fun secrets(c: Context): Set<String> = prefs(c).getStringSet("secrets", emptySet()) ?: emptySet()

    /** The JSON, made to match the approved secrets (migrates PCs approved by older versions, drops removed ones). */
    private fun synced(c: Context): JSONObject {
        val sec = secrets(c); val o = read(c); var dirty = false
        for (k in o.keys().asSequence().toList()) if (k !in sec) { o.remove(k); dirty = true }
        for (s in sec) if (!o.has(s)) { o.put(s, JSONObject().put("name", "PC ${o.length() + 1}").put("created", System.currentTimeMillis())); dirty = true }
        if (dirty) write(c, o)
        return o
    }

    fun list(c: Context): List<Pc> = synchronized(lock) {
        val o = synced(c)
        o.keys().asSequence().map { k ->
            val e = o.getJSONObject(k)
            Triple(e.optLong("created"), k, Pc(k, e.optString("name", "PC"), e.optBoolean("named"), e.has("hash"), e.optJSONArray("macs")?.length() ?: 0,
                e.optLong("lastSeen"), e.optString("lastIp")))
        }.sortedBy { it.first }.map { it.third }.toList()
    }

    /** A request from this PC was just authenticated: remember when / from where, and take over the name it sends. Returns its name. */
    fun touch(c: Context, secret: String, ip: String, clientName: String): String = synchronized(lock) {
        val o = synced(c); val e = o.optJSONObject(secret) ?: return@synchronized "?"
        val now = System.currentTimeMillis(); var dirty = false
        val nm = clean(clientName)
        if (nm.isNotEmpty() && (e.optString("name") != nm || !e.optBoolean("named"))) { e.put("name", nm).put("named", true); dirty = true }
        if (now - e.optLong("lastSeen") > 30_000 || e.optString("lastIp") != ip) { e.put("lastSeen", now).put("lastIp", ip); dirty = true }   // at most one write per 30 s
        if (dirty) write(c, o)
        e.optString("name")
    }
    private fun clean(s: String) = s.filter { !it.isISOControl() }.trim().take(40)

    /** Take this PC off the approved list: its token stops working at once. */
    fun remove(c: Context, secret: String) = synchronized(lock) {
        prefs(c).edit().putStringSet("secrets", HashSet(secrets(c)) - secret).apply()
        val o = read(c); o.remove(secret); write(c, o); fails.remove(secret); Unit
    }
    fun removeAll(c: Context) = synchronized(lock) { prefs(c).edit().remove("secrets").remove("pcs").apply(); fails.clear() }

    // ---- wake PIN (separate for every PC; asked once per target MAC address)
    fun validPin(p: String) = Regex("\\d{4,16}").matches(p)
    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)).encoded
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Sets (or changes) the PIN. Every MAC this PC had unlocked has to be unlocked again with the new PIN. */
    fun setPin(c: Context, secret: String, pin: String) = synchronized(lock) {
        require(validPin(pin)) { "PIN = 4 to 16 digits" }
        val o = synced(c); val e = o.optJSONObject(secret) ?: return@synchronized
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        e.put("salt", salt.hex()).put("hash", hash(pin, salt).hex()).remove("macs"); write(c, o); fails.remove(secret); Unit
    }
    fun clearPin(c: Context, secret: String) = synchronized(lock) {
        val o = synced(c); val e = o.optJSONObject(secret) ?: return@synchronized
        e.remove("salt"); e.remove("hash"); e.remove("macs"); write(c, o); fails.remove(secret); Unit
    }
    fun hasPin(c: Context, secret: String): Boolean = synchronized(lock) { synced(c).optJSONObject(secret)?.has("hash") == true }

    fun checkPin(c: Context, secret: String, pin: String): PinResult = synchronized(lock) {
        val now = System.currentTimeMillis(); val f = fails[secret]
        if (f != null && f.second > now) return@synchronized PinResult.Locked(((f.second - now) / 1000).toInt() + 1)
        val e = synced(c).optJSONObject(secret) ?: return@synchronized PinResult.Bad
        val ok = e.has("hash") && MessageDigest.isEqual(hash(pin, e.getString("salt").unhex()), e.getString("hash").unhex())
        if (ok) { fails.remove(secret); PinResult.Ok }
        else {
            val n = (f?.first ?: 0) + 1
            if (n >= MAX_FAILS) { fails[secret] = 0 to now + LOCK_MS; PinResult.Locked((LOCK_MS / 1000).toInt()) } else { fails[secret] = n to 0L; PinResult.Bad }
        }
    }

    fun normMac(m: String) = m.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
    fun isUnlocked(c: Context, secret: String, mac: String): Boolean = synchronized(lock) {
        val a = synced(c).optJSONObject(secret)?.optJSONArray("macs") ?: return@synchronized false
        (0 until a.length()).any { a.getString(it) == normMac(mac) }
    }
    fun unlock(c: Context, secret: String, mac: String) = synchronized(lock) {
        val o = synced(c); val e = o.optJSONObject(secret) ?: return@synchronized
        val a = e.optJSONArray("macs") ?: JSONArray().also { e.put("macs", it) }
        if ((0 until a.length()).none { a.getString(it) == normMac(mac) }) { a.put(normMac(mac)); write(c, o) }
        Unit
    }
}
