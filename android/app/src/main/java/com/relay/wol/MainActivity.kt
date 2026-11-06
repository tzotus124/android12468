package com.relay.wol
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.URL

const val TS_PKG = "com.tailscale.ipn"

/** Mints a one-time Tailscale auth key (valid 1 hour) using the owner's API access token. */
fun createAuthKey(api: String): String {
    val c = URL("https://api.tailscale.com/api/v2/tailnet/-/keys").openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10000; c.readTimeout = 10000
    c.setRequestProperty("Authorization", "Bearer $api"); c.setRequestProperty("Content-Type", "application/json")
    c.outputStream.use { it.write("""{"capabilities":{"devices":{"create":{"reusable":false,"ephemeral":false,"preauthorized":true}}},"expirySeconds":3600}""".toByteArray()) }
    val ok = c.responseCode in 200..299
    val txt = (if (ok) c.inputStream else c.errorStream).bufferedReader().readText()
    if (!ok) throw Exception("HTTP ${c.responseCode}: ${txt.take(100)}")
    return org.json.JSONObject(txt).getString("key")
}

class MainActivity : ComponentActivity() {
    private var clipKey by mutableStateOf<String?>(null)
    override fun onWindowFocusChanged(f: Boolean) {
        super.onWindowFocusChanged(f)
        if (!f) return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        if (t != null && t.startsWith("tskey-api-")) { clipKey = t; if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() }
    }
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        if (prefs(this).getBoolean("on", true)) startForegroundService(Intent(this, RelayService::class.java))
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6C8CFF))) { Screen() } }
    }

    private fun tsInstalled() = runCatching { packageManager.getPackageInfo(TS_PKG, 0); true }.getOrDefault(false)
    private fun batteryOk() = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
    private fun installTs() = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TS_PKG"))) }
        .onFailure { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TS_PKG"))) }
    private fun openTs() { packageManager.getLaunchIntentForPackage(TS_PKG)?.let { startActivity(it) } }
    private fun askBattery() = startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))

    @Composable fun Card(content: @Composable ColumnScope.() -> Unit) =
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0x14FFFFFF)).padding(20.dp), content = content)

    @Composable fun StepRow(done: Boolean, title: String, action: String, enabled: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✔" else "○", color = if (done) Color(0xFF22C55E) else Color.Gray, fontSize = 20.sp)
            Text(title, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            if (!done) Button(onClick, enabled = enabled) { Text(action) }
        }
    }

    @Composable fun Screen() {
        var installed by remember { mutableStateOf(tsInstalled()) }
        var ip by remember { mutableStateOf<String?>(null) }
        var batt by remember { mutableStateOf(batteryOk()) }
        var on by remember { mutableStateOf(prefs(this).getBoolean("on", true)) }
        var apiKey by remember { mutableStateOf(prefs(this).getString("api", "") ?: "") }
        var pcToken by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf("") }
        var log by remember { mutableStateOf(loadLog(this)) }
        var tried by rememberSaveable { mutableStateOf(0) }   // each step auto-opens once

        LaunchedEffect(Unit) { while (true) { installed = tsInstalled(); ip = tailscaleIp(); batt = batteryOk(); log = loadLog(this@MainActivity); delay(1500) } }
        LaunchedEffect(installed, ip, batt) {
            when {
                !installed && tried and 1 == 0 -> { tried = tried or 1; installTs() }
                installed && ip == null && tried and 2 == 0 -> { tried = tried or 2; openTs() }
                installed && ip != null && !batt && tried and 4 == 0 -> { tried = tried or 4; askBattery() }
            }
        }
        val ready = installed && ip != null && batt
        val generate: () -> Unit = {
            msg = "Generating…"; pcToken = ""
            Thread { try {
                val myIp = tailscaleIp() ?: throw Exception("Tailscale is not connected on this phone")
                val key = createAuthKey(apiKey.trim())
                val sec = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
                val old = prefs(this@MainActivity).getStringSet("secrets", emptySet()) ?: emptySet()
                prefs(this@MainActivity).edit().putStringSet("secrets", HashSet(old) + sec).apply()
                pcToken = "$key~$sec~$myIp"; msg = "Done. Paste this token into the PC app."
            } catch (e: Exception) {
                if (e.message?.contains("HTTP 401") == true || e.message?.contains("HTTP 403") == true) {
                    apiKey = ""; prefs(this@MainActivity).edit().remove("api").apply()
                    msg = "Tailscale access expired. Tap the button to sign in again."
                } else msg = "Failed: ${e.message}" } }.start()
        }
        LaunchedEffect(clipKey) { clipKey?.let { apiKey = it; prefs(this@MainActivity).edit().putString("api", it).apply(); clipKey = null; generate() } }

        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF1A1440))))
            .statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("⏻ WoL Relay", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            if (!ready) Card {
                Text("Setup", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text("Finish these steps once. Each opens automatically and this screen updates when it's done.",
                    color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp))
                StepRow(installed, "Install Tailscale", "Install", true) { installTs() }
                StepRow(ip != null, "Sign in to Tailscale", "Sign in", installed) { openTs() }
                StepRow(batt, "Keep relay always on", "Allow", true) { askBattery() }
            } else Card { Text("✔ Setup complete", color = Color(0xFF22C55E), fontWeight = FontWeight.SemiBold) }
            Card {
                Text("PC token", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("Generates the token your PC needs to connect. First time only: the app opens Tailscale so you can sign in and copy an access token; it picks it up automatically.",
                    color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
                Button({ if (apiKey.isBlank()) {
                        msg = "Sign in, tap \"Generate access token\", then Copy. Come back here and I'll finish automatically."
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://login.tailscale.com/admin/settings/keys")))
                    } else generate() },
                    Modifier.fillMaxWidth()) { Text(if (apiKey.isBlank()) "Get access from Tailscale & generate token" else "Generate PC token") }
                if (msg.isNotEmpty()) Text(msg, color = Color(0xFF9DB2FF), modifier = Modifier.padding(top = 8.dp))
                OutlinedButton({ prefs(this@MainActivity).edit().remove("secrets").apply(); pcToken = ""; msg = "All PCs unpaired." },
                    Modifier.fillMaxWidth()) { Text("Unpair all PCs") }
                if (pcToken.isNotEmpty()) {
                    Text(pcToken, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                    OutlinedButton({ (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("t", pcToken)) },
                        Modifier.fillMaxWidth()) { Text("Copy token") }
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Relay service", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(if (on) "Running in background" else "Stopped", color = if (on) Color(0xFF22C55E) else Color(0xFFEF4444))
                    }
                    Switch(on, { on = it; prefs(this@MainActivity).edit().putBoolean("on", it).apply()
                        val i = Intent(this@MainActivity, RelayService::class.java)
                        if (it) startForegroundService(i) else stopService(i) })
                }
            }
            Card {
                Text("Tailscale", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(if (ip != null) "● Connected  $ip" else "● Not connected", color = if (ip != null) Color(0xFF22C55E) else Color(0xFFF59E0B))
                Text("Link your PC with a token: generate one in the PC token card above and paste it into the PC app.", color = Color(0xFF9DB2FF), modifier = Modifier.padding(top = 8.dp))
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wake log", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton({ prefs(this@MainActivity).edit().remove("log").apply(); log = emptyList() }) { Text("Clear") }
                }
                if (log.isEmpty()) Text("No wake requests yet.", color = Color.Gray)
                log.take(40).forEach { Text(it, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}
