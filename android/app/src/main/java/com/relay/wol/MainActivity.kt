package com.relay.wol
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        if (prefs(this).getBoolean("on", true)) startForegroundService(Intent(this, RelayService::class.java))
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF6C8CFF))) { Screen() } }
    }

    @Composable fun Card(content: @Composable ColumnScope.() -> Unit) =
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0x14FFFFFF)).padding(20.dp), content = content)

    @Composable fun Screen() {
        var on by remember { mutableStateOf(prefs(this).getBoolean("on", true)) }
        var ip by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { while (true) { ip = tailscaleIp(); delay(2000) } }
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF1A1440))))
            .padding(20.dp).statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("⏻ WoL Relay", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                Spacer(Modifier.height(10.dp))
                Button({ val i = packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                    startActivity(i ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.tailscale.ipn"))) },
                    Modifier.fillMaxWidth()) { Text(if (ip != null) "Open Tailscale" else "Sign in / Install Tailscale") }
            }
            Card {
                Text("PC connection", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text("No pairing needed. Your PC finds this phone automatically through Tailscale (port $PORT).",
                    color = Color(0xFF9DB2FF), modifier = Modifier.padding(top = 8.dp))
            }
            OutlinedButton({ startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) },
                Modifier.fillMaxWidth()) { Text("Disable battery optimization (keep always on)") }
        }
    }
}
