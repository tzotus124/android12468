package com.wolrelay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets with a key that lives in the Android Keystore (never leaves the device's secure hardware/TEE). */
object Secure {
    private const val ALIAS = "wolrelay-secret"
    private fun key(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null))?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(text: String): String? = runCatching {
        val raw = Base64.decode(text, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()
}

/**
 * Creates a fresh single-use, pre-approved, 10-minute Tailscale auth key through the Tailscale API every time a token is generated.
 * Credential (entered once, stored encrypted): a Tailscale API access token "tskey-api-…", or an OAuth client "clientId:clientSecret"
 * (OAuth needs the auth_keys scope and a tag, e.g. tag:wake-pc, which must exist in your policy file's tagOwners).
 */
object TailscaleApi {
    private const val API = "https://api.tailscale.com/api/v2"

    fun validate(cred: String): String? = when {
        cred.startsWith("tskey-api-") -> null
        cred.count { it == ':' } == 1 && cred.all { !it.isWhitespace() } -> null
        else -> "Enter a Tailscale API token (tskey-api-…) or an OAuth client as clientId:clientSecret"
    }

    private fun call(url: String, method: String, headers: Map<String, String>, body: String?, ct: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 10000; c.readTimeout = 15000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", ct); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code >= 400) error("Tailscale API $code: ${runCatching { JSONObject(text).optString("message") }.getOrDefault("").ifEmpty { text.take(120) }}")
        return JSONObject(text)
    }

    private fun bearer(cred: String): String {
        if (cred.startsWith("tskey-api-")) return cred
        val (id, secret) = cred.split(":")
        return call("$API/oauth/token", "POST",
            mapOf("Authorization" to "Basic " + Base64.encodeToString("$id:$secret".toByteArray(), Base64.NO_WRAP)),
            "grant_type=client_credentials", "application/x-www-form-urlencoded").getString("access_token")
    }

    fun createAuthKey(cred: String, tag: String): String {
        val tags = tag.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (!cred.startsWith("tskey-api-") && tags.isEmpty()) error("OAuth clients need a tag, e.g. tag:wake-pc")
        val create = JSONObject().put("reusable", false).put("ephemeral", false).put("preauthorized", true)
        if (tags.isNotEmpty()) create.put("tags", JSONArray(tags))
        val body = JSONObject().put("capabilities", JSONObject().put("devices", JSONObject().put("create", create)))
            .put("expirySeconds", 600).put("description", "wake-relay pairing")
        return call("$API/tailnet/-/keys", "POST", mapOf("Authorization" to "Bearer ${bearer(cred)}"), body.toString(), "application/json").getString("key")
    }
}
