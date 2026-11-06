package com.wolrelay

import android.Manifest
import android.content.Intent
import android.net.Uri
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

private const val TS_PKG = "com.tailscale.ipn"

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
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); Relay.refreshTailnet(); delay(1000) } }
    val begin = { Relay.linkWithCode(code); RelayService.start(ctx) }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(st.openUrl) {  // open Tailscale's approval page for the PC (your browser is usually already signed in)
        st.openUrl?.let { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; Relay.urlOpened() }
    }
    val tsInstalled = { runCatching { ctx.packageManager.getPackageInfo(TS_PKG, 0); true }.getOrDefault(false) }
    val openTailscale = {
        val i = ctx.packageManager.getLaunchIntentForPackage(TS_PKG)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TS_PKG"))
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TS_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // Startup setup, one step per launch of the app (and again each time the user returns to it):
    // 1 notifications  2 this app's battery exemption  3 install Tailscale if missing  4 Tailscale's battery exemption.
    val asked = remember { BooleanArray(4) }
    val proceedRef = remember { arrayOf<() -> Unit>({}) }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { proceedRef[0]() }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { proceedRef[0]() }
    proceedRef[0] = {
        val pm = ctx.getSystemService(PowerManager::class.java)
        when {
            Build.VERSION.SDK_INT >= 33 && !asked[0] && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                { asked[0] = true; notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
            !asked[1] && !pm.isIgnoringBatteryOptimizations(ctx.packageName) ->
                { asked[1] = true; battery.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
            !asked[2] && !tsInstalled() -> { asked[2] = true; openTailscale() }
            !asked[3] && tsInstalled() && !Prefs.tsBatteryAsked -> {
                // Android only lets an app request its OWN exemption, so for Tailscale we open the system list where the user flips it.
                asked[3] = true; Prefs.tsBatteryAsked = true
                android.widget.Toast.makeText(ctx, "Set Tailscale to “Don't optimize” / Unrestricted", android.widget.Toast.LENGTH_LONG).show()
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) proceedRef[0]() }
        lifecycle.addObserver(o); onDispose { lifecycle.removeObserver(o) }
    }
    LaunchedEffect(Unit) { if (Prefs.paired) RelayService.start(ctx) }

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
            Panel("1. Sign in to Tailscale", "Required on this phone. Wake Relay offers to install Tailscale if it is missing; sign in there, then come back.") {
                if (st.tailnetIp != null) Text("Signed in · ${st.tailnetIp}", color = Accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                else if (!tsInstalled()) Primary("Install Tailscale") { openTailscale() }
                else Primary("Open Tailscale to sign in") { openTailscale() }
            }
            Panel("2. Link a PC", "On the PC app press Start. It shows a 6-digit code. Enter it here, then approve the PC in the Tailscale page that opens. No keys or tokens needed.") {
                val ready = st.tailnetIp != null
                if (st.linking) {
                    Text("Approve the PC in your browser. This phone links automatically once the PC is online.", color = Muted, fontSize = 13.sp)
                    Row(Modifier.padding(top = 8.dp)) {
                        st.lastUrl?.let { u -> TextButton({ runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open approval page", color = Accent) } }
                        Spacer(Modifier.weight(1f)); TextButton({ Relay.unpair() }) { Text("Cancel", color = Bad) }
                    }
                } else {
                    OutlinedTextField(code, { v -> code = v.filter { it.isDigit() }.take(6) }, Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        label = { Text("6-digit code from the PC") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword))
                    if (ready && code.length == 6) Primary("Link") { begin() }
                    else Text(if (!ready) "Sign in to Tailscale first." else "Enter the code shown on the PC.", color = Muted, fontSize = 12.sp)
                }
            }
        } else {
            Panel("Linked to a PC", "Check this matches the fingerprint on the PC app.") {
                Text(st.fingerprint, color = Ink, fontSize = 22.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                if (st.tailnetIp != null) Text("Tailscale: connected · ${st.tailnetIp}", color = Muted, fontSize = 13.sp)
                else { Text("Tailscale: signed out or off. The PC can't reach this phone.", color = Bad, fontSize = 13.sp); TextButton({ openTailscale() }) { Text("Open Tailscale", color = Accent) } }
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
