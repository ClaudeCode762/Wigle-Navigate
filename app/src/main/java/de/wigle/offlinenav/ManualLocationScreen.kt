package de.wigle.offlinenav

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale

private data class Preset(val name: String, val lat: Double, val lon: Double)

private val PRESETS = listOf(
    Preset("Berlin, Brandenburger Tor", 52.516275, 13.377704),
    Preset("München, Marienplatz", 48.137154, 11.576124),
    Preset("Hamburg, Rathaus", 53.550341, 10.000654),
    Preset("Köln, Dom", 50.941278, 6.958281),
    Preset("Frankfurt, Römer", 50.110556, 8.682222),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualLocationScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val nav by WifiNavService.state.collectAsStateWithLifecycle()
    val manual by WifiNavService.manual.collectAsStateWithLifecycle()
    val store = vm.manualStore

    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val mockOk = remember(resumeTick, nav.mockActive) { MockLocationPublisher.isMockLocationApp(ctx) }
    val hasPerm = remember(resumeTick) { WifiNavService.hasLocationPermission(ctx) }

    var lat by rememberSaveable { mutableStateOf(store.lat) }
    var lon by rememberSaveable { mutableStateOf(store.lon) }
    var acc by rememberSaveable { mutableStateOf(store.accuracy) }
    var step by rememberSaveable { mutableStateOf(store.stepMeters) }

    fun num(v: String) = v.trim().replace(',', '.').toDoubleOrNull()
    val candidate = run {
        val a = num(lat); val b = num(lon); val c = num(acc)?.toFloat()
        if (a != null && b != null && c != null) ManualLocation(a, b, c) else null
    }
    val error = if (candidate == null) "Breiten-, Längengrad und Genauigkeit angeben" else candidate.validate()

    fun fill(la: Double, lo: Double) {
        lat = ManualLocation.format(la); lon = ManualLocation.format(lo)
    }

    var pending by remember { mutableStateOf<ManualLocation?>(null) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        resumeTick++
        val p = pending
        pending = null
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true && p != null) {
            WifiNavService.setManualLocation(ctx, p)
        } else {
            Toast.makeText(ctx, "Genauer Standort wird für den Foreground-Service benötigt", Toast.LENGTH_LONG).show()
        }
    }

    fun applyLocation(loc: ManualLocation) {
        store.lat = lat; store.lon = lon; store.accuracy = acc; store.stepMeters = step
        if (WifiNavService.hasLocationPermission(ctx)) {
            WifiNavService.setManualLocation(ctx, loc)
        } else {
            pending = loc
            permLauncher.launch(requiredPermissions())
        }
    }

    fun move(northM: Double, eastM: Double) {
        val base = candidate?.takeIf { error == null } ?: return
        val moved = base.offset(northM, eastM)
        fill(moved.latitude, moved.longitude)
        if (manual != null) applyLocation(moved)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Test-Standort", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Speist eine frei gewählte Position statt der WLAN-Schätzung ein – zum Prüfen, ob die " +
                    "simulierte Position in Karten-Apps ankommt.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (!mockOk) item {
            WarnCard(
                title = "Mock-Location nicht aktiv",
                text = "Wähle diese App unter Entwickleroptionen → „App für simulierte Standorte auswählen“.",
                action = "Entwickleroptionen öffnen",
            ) { openDeveloperOptions(ctx) }
        }

        // ── Status ──
        item {
            SectionCard("Status") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            manual != null && nav.running -> "● TEST-STANDORT AKTIV"
                            nav.running -> "● WLAN-Navigation aktiv"
                            else -> "○ INAKTIV"
                        },
                        color = if (manual != null && nav.running) Color(0xFFE65100)
                        else if (nav.running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
                manual?.let { m ->
                    SelectionContainer {
                        Text(
                            String.format(Locale.ROOT, "%.6f, %.6f", m.latitude, m.longitude),
                            fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    StatRow("Genauigkeit", String.format(Locale.ROOT, "± %.0f m", m.accuracyMeters))
                }
                StatRow("Mock-Location", when {
                    nav.mockActive -> "eingespeist"
                    nav.mockError != null -> nav.mockError!!
                    else -> "—"
                })
                if (manual != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { WifiNavService.clearManualLocation() }) { Text("Zurück zu WLAN") }
                        Button(
                            onClick = { WifiNavService.stop(ctx) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Stopp") }
                    }
                }
            }
        }

        // ── Eingabe ──
        item {
            SectionCard("Koordinaten") {
                val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(lat, { lat = it }, Modifier.weight(1f), label = { Text("Breitengrad") }, singleLine = true, keyboardOptions = dec)
                    OutlinedTextField(lon, { lon = it }, Modifier.weight(1f), label = { Text("Längengrad") }, singleLine = true, keyboardOptions = dec)
                }
                OutlinedTextField(
                    acc, { acc = it }, Modifier.fillMaxWidth(),
                    label = { Text("Genauigkeit (m)") }, singleLine = true, keyboardOptions = dec,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { candidate?.let { applyLocation(it) } },
                    enabled = error == null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (manual != null) "Test-Standort aktualisieren" else "Test-Standort setzen") }
            }
        }

        // ── Schnellauswahl ──
        item {
            SectionCard("Schnellauswahl") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val p = pasteCoordinates(ctx)
                        if (p != null) fill(p.first, p.second)
                        else Toast.makeText(ctx, "Keine Koordinaten in der Zwischenablage", Toast.LENGTH_SHORT).show()
                    }) { Text("Aus Zwischenablage") }
                    nav.estimate?.let { est ->
                        OutlinedButton(onClick = { fill(est.latitude, est.longitude) }) { Text("WLAN-Position") }
                    }
                    OutlinedButton(onClick = {
                        vm.randomAccessPoint { ap ->
                            if (ap != null) fill(ap.latitude, ap.longitude)
                            else Toast.makeText(ctx, "Datenbank ist leer", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Zufälliger AP aus DB") }
                }
                Text(
                    "Zwischenablage erkennt „lat, lon“, geo:-URIs sowie Google-Maps- und OSM-Links.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                PRESETS.forEach { p ->
                    TextButton(onClick = { fill(p.lat, p.lon) }) {
                        Text(String.format(Locale.ROOT, "%s  (%.4f, %.4f)", p.name, p.lat, p.lon))
                    }
                }
            }
        }

        // ── Bewegung simulieren ──
        item {
            SectionCard("Bewegen") {
                Text(
                    "Verschiebt die Koordinaten; bei aktivem Test-Standort wird sofort neu eingespeist.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    step, { step = it }, Modifier.width(160.dp),
                    label = { Text("Schritt (m)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                val s = num(step)?.takeIf { it > 0 }
                val enabled = s != null && error == null
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { move(s!!, 0.0) }, enabled = enabled) { Text("▲ Nord") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { move(0.0, -s!!) }, enabled = enabled) { Text("◀ West") }
                        Button(onClick = { move(0.0, s!!) }, enabled = enabled) { Text("Ost ▶") }
                    }
                    Button(onClick = { move(-s!!, 0.0) }, enabled = enabled) { Text("▼ Süd") }
                }
            }
        }

        // ── Prüfung: was meldet das System? ──
        item {
            SystemLocationCheck(hasPerm = hasPerm, running = nav.running, target = manual)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Lauscht wie eine Karten-App auf GPS- und Network-Provider und zeigt, was Android tatsächlich ausliefert.
 * Stimmt die empfangene Position mit dem Test-Standort überein, funktioniert die Einspeisung.
 */
@SuppressLint("MissingPermission")
@Composable
private fun SystemLocationCheck(hasPerm: Boolean, running: Boolean, target: ManualLocation?) {
    val ctx = LocalContext.current
    val received = remember { mutableStateMapOf<String, Location>() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }

    LaunchedEffect(Unit) {
        while (true) { delay(1_000); now = SystemClock.elapsedRealtimeNanos() }
    }

    // Neu registrieren, wenn der Service (und damit die Testprovider) startet/stoppt.
    DisposableEffect(hasPerm, running) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { loc -> received[loc.provider ?: "?"] = loc }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        if (hasPerm) {
            for (p in providers) {
                try {
                    if (p !in lm.allProviders) continue
                    lm.getLastKnownLocation(p)?.let { received[p] = it }
                    lm.requestLocationUpdates(p, 1_000L, 0f, listener, Looper.getMainLooper())
                } catch (_: Exception) { }
            }
        }
        onDispose { try { lm.removeUpdates(listener) } catch (_: Exception) { } }
    }

    SectionCard("Prüfung: Was meldet Android?") {
        if (!hasPerm) {
            Text("Standort-Berechtigung fehlt.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        Text(
            "Diese Werte empfängt jede App (z. B. OsmAnd) vom System.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = received[p]
            Text(p.uppercase(Locale.ROOT), fontWeight = FontWeight.Bold)
            if (loc == null) {
                Text("noch nichts empfangen", style = MaterialTheme.typography.bodySmall)
            } else {
                val ageS = ((now - loc.elapsedRealtimeNanos) / 1_000_000_000L).coerceAtLeast(0)
                Text(
                    String.format(Locale.ROOT, "%.6f, %.6f ±%.0f m", loc.latitude, loc.longitude, loc.accuracy),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                )
                StatRow("Alter", "$ageS s")
                StatRow("Als Mock markiert", if (loc.isMockCompat()) "ja" else "nein")
                target?.let { t ->
                    val d = LocationCalculator.haversine(t.latitude, t.longitude, loc.latitude, loc.longitude)
                    StatRow("Abstand zum Test-Standort", String.format(Locale.ROOT, "%.1f m", d))
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        val gps = received[LocationManager.GPS_PROVIDER]
        if (target != null && running) {
            val ok = gps != null &&
                (now - gps.elapsedRealtimeNanos) < 5_000_000_000L &&
                LocationCalculator.haversine(target.latitude, target.longitude, gps.latitude, gps.longitude) < 1.0
            Text(
                if (ok) "✓ Simulierter Standort funktioniert – das System liefert die Test-Position aus."
                else "✗ Test-Position kommt (noch) nicht an. Mock-App in den Entwickleroptionen prüfen.",
                color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun Location.isMockCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock
    else @Suppress("DEPRECATION") isFromMockProvider

private fun pasteCoordinates(ctx: Context): Pair<Double, Double>? {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val text = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return null
    return ManualLocation.parse(text)
}
