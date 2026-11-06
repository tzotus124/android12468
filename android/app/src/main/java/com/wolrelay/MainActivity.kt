package com.wolrelay

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0D1117); private val Card = Color(0xFF161B22); private val Ink = Color(0xFFE6EDF3)
private val Muted = Color(0xFF8B949E); private val Accent = Color(0xFF3DDC97); private val Bad = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Relay.init(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card, onPrimary = Color(0xFF06240F))) { Screen() }
        }
    }
}

@Composable
private fun Screen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by Relay.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val begin = { Relay.startPairing(BuildConfig.RELAY_SERVER); RelayService.start(ctx) }
    val vpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == -1) begin() }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS); if (Prefs.paired) RelayService.start(ctx) }

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).statusBarsPadding().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wake Relay", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Card).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (st.running) Accent else Muted))
                    Spacer(Modifier.width(8.dp)); Text(st.status, color = Muted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))

        if (!st.paired) {
            Panel("Link a PC", "Tap Generate token, then type the 6 digits into the PC app.") {
                val left = ((st.tokenExpires - now) / 1000).coerceAtLeast(0)
                if (st.token != null && left > 0) {
                    Text(st.token!!.chunked(3).joinToString("  "), color = Accent, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                    Text("Expires in ${left}s", color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                } else Primary("Generate token") {
                    val i = VpnService.prepare(ctx); if (i != null) vpn.launch(i) else begin()
                }
            }
        } else {
            Panel("Linked to a PC", "Check this matches the fingerprint on the PC app.") {
                Text(st.fingerprint, color = Ink, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text("Tunnel: " + if (st.tunnelUp) "open" else "closed (opens when the PC asks)", color = Muted, fontSize = 13.sp)
                Text("Last wake: ${st.lastWake}", color = Muted, fontSize = 13.sp)
                TextButton({ Relay.unpair() }) { Text("Unlink", color = Bad) }
            }
            Panel("Always on", "Keeps listening so the PC can reach this phone. Stays on after a restart.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (st.running) "Running" else "Stopped", color = Ink, modifier = Modifier.weight(1f))
                    Switch(st.running, { if (it) RelayService.start(ctx) else RelayService.stop(ctx) })
                }
                val pm = ctx.getSystemService(PowerManager::class.java)
                if (!pm.isIgnoringBatteryOptimizations(ctx.packageName))
                    TextButton({ ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }) { Text("Allow background activity", color = Accent) }
            }
        }
        Panel("Activity", null) { st.log.take(8).forEach { Text(it, color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }; if (st.log.isEmpty()) Text("Nothing yet.", color = Muted, fontSize = 12.sp) }
    }
}

@Composable private fun Panel(title: String, sub: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(RoundedCornerShape(20.dp)).background(Card).padding(20.dp)) {
        Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)) else Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable private fun Primary(text: String, onClick: () -> Unit) =
    Button(onClick, Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) { Text(text, fontWeight = FontWeight.Bold) }
