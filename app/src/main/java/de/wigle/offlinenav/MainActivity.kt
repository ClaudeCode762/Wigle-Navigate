package de.wigle.offlinenav

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

// ───────────────────────────── ViewModel ─────────────────────────────

data class ImportState(
    val running: Boolean = false,
    val progress: Long = 0,
    val result: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).accessPointDao()
    private val importer = CsvImporter(app)
    private var importJob: Job? = null

    val apCount: StateFlow<Long> = dao.countFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val importState = MutableStateFlow(ImportState())

    fun import(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            importState.value = ImportState(running = true)
            val msg = try {
                val r = importer.import(uri) { n -> importState.value = importState.value.copy(progress = n) }
                "Importiert: ${r.imported} · übersprungen: ${r.skippedNonWifi} kein WLAN, " +
                    "${r.skippedInvalid} ungültig · Zeilen: ${r.linesRead}"
            } catch (e: Exception) {
                "Fehler: ${e.message}"
            }
            importState.value = ImportState(running = false, result = msg)
        }
    }

    fun cancelImport() { importJob?.cancel() }

    fun clearDatabase() {
        viewModelScope.launch {
            dao.clear()
            importState.value = ImportState(result = "Datenbank gelöscht")
        }
    }
}

// ───────────────────────────── Activity ─────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFF81C784))
                else lightColorScheme(primary = Color(0xFF1B5E20))
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
}.toTypedArray()

private const val ADB_CMD = "adb shell settings put global wifi_scan_throttle_enabled 0"

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val ctx = LocalContext.current
    val nav by WifiNavService.state.collectAsStateWithLifecycle()
    val apCount by vm.apCount.collectAsStateWithLifecycle()
    val importState by vm.importState.collectAsState()

    // Systemzustände bei jedem Resume neu prüfen
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val mockOk = remember(resumeTick, nav.mockActive) { MockLocationPublisher.isMockLocationApp(ctx) }
    val hasPerm = remember(resumeTick) { WifiNavService.hasLocationPermission(ctx) }
    val locationOn = remember(resumeTick) { isLocationEnabled(ctx) }
    val wifiScanOn = remember(resumeTick) { isWifiScanPossible(ctx) }

    var confirmDelete by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        resumeTick++
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) WifiNavService.start(ctx)
        else Toast.makeText(ctx, "Genauer Standort wird für WLAN-Scans benötigt", Toast.LENGTH_LONG).show()
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.import(it) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("WiGLE Offline Navigator", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Positionsbestimmung ohne GPS & Internet", style = MaterialTheme.typography.bodyMedium)
        }

        // ── Warnungen ──
        if (!mockOk) item {
            WarnCard(
                title = "Mock-Location nicht aktiv",
                text = "Wähle diese App unter Entwickleroptionen → „App für simulierte Standorte auswählen“.",
                action = "Entwickleroptionen öffnen",
            ) { openDeveloperOptions(ctx) }
        }
        if (!hasPerm) item {
            WarnCard("Berechtigung fehlt", "Genauer Standort ist für WLAN-Scan-Ergebnisse nötig.", "Erteilen") {
                permLauncher.launch(requiredPermissions())
            }
        }
        if (!locationOn) item {
            WarnCard("Standortdienste aus", "Android liefert WLAN-Scans nur bei aktivem Standort (GPS-Empfang ist nicht nötig).", "Einstellungen") {
                ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        if (!wifiScanOn) item {
            WarnCard("WLAN aus", "WLAN oder „WLAN-Suche“ muss aktiviert sein.", "Einstellungen") {
                ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }

        // ── Status-Dashboard ──
        item {
            SectionCard("Status") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (nav.running) "● AKTIV" else "○ INAKTIV",
                        color = if (nav.running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (nav.running) {
                        Button(
                            onClick = { WifiNavService.stop(ctx) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Stopp") }
                    } else {
                        Button(
                            onClick = { permLauncher.launch(requiredPermissions()) },
                            enabled = apCount > 0,
                        ) { Text("Navigation starten") }
                    }
                }
                if (apCount == 0L && !nav.running) Text("Zuerst eine WiGLE-CSV importieren.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                StatRow("Bekannte WLANs", nav.knownNetworks.toString())
                StatRow("Unbekannte WLANs", nav.unknownNetworks.toString())
                StatRow("Sichtbar gesamt", nav.visibleNetworks.toString())
                StatRow("Scans", nav.scanCount.toString() + if (nav.throttled) " (gedrosselt)" else "")
                StatRow("Mock-Location", when {
                    nav.mockActive -> "eingespeist"
                    nav.mockError != null -> nav.mockError!!
                    else -> "—"
                })
                nav.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            }
        }

        // ── Live-Daten ──
        item {
            SectionCard("Position") {
                val est = nav.estimate
                if (est == null) {
                    Text("Noch keine Position berechnet.")
                } else {
                    SelectionContainer {
                        Text(
                            String.format(Locale.ROOT, "%.6f, %.6f", est.latitude, est.longitude),
                            fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    StatRow("Genauigkeit", String.format(Locale.ROOT, "± %.0f m", est.accuracyMeters))
                    StatRow("Genutzte APs", "${est.used.size}" + if (est.rejectedOutliers > 0) " (${est.rejectedOutliers} Ausreißer verworfen)" else "")
                    val ageS = (System.currentTimeMillis() - nav.estimateAt) / 1000
                    StatRow("Alter", "$ageS s")
                }
            }
        }
        nav.estimate?.let { est ->
            item { Text("Verwendete Access Points", style = MaterialTheme.typography.titleMedium) }
            items(est.used, key = { it.bssid }) { ap -> ApRow(ap) }
        }

        // ── Datenbank ──
        item {
            SectionCard("WiGLE-Datenbank") {
                StatRow("Gespeicherte Access Points", "%,d".format(apCount))
                Spacer(Modifier.height(8.dp))
                if (importState.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Importiere … %,d".format(importState.progress), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::cancelImport) { Text("Abbrechen") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            fileLauncher.launch(arrayOf("text/*", "application/gzip", "application/x-gzip", "application/octet-stream", "*/*"))
                        }) { Text("CSV importieren") }
                        OutlinedButton(onClick = { confirmDelete = true }, enabled = apCount > 0) { Text("Löschen") }
                    }
                }
                importState.result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        // ── ADB-Hinweis ──
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Tipp: Scan-Drosselung abschalten", fontWeight = FontWeight.Bold)
                    Text(
                        "Android erlaubt sonst nur 4 Scans / 2 min. Für flüssige Updates per PC ausführen:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer { Text(ADB_CMD, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    Text(
                        "Alternativ: Entwickleroptionen → „Drosselung der WLAN-Suche“ deaktivieren.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("adb", ADB_CMD))
                        Toast.makeText(ctx, "Kopiert", Toast.LENGTH_SHORT).show()
                    }) { Text("Befehl kopieren") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Datenbank löschen?") },
            text = { Text("Alle %,d Access Points werden entfernt.".format(apCount)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.clearDatabase() }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun WarnCard(title: String, text: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ApRow(ap: UsedAccessPoint) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ap.ssid.ifEmpty { "<versteckt>" }, fontWeight = FontWeight.Medium)
                Text(ap.bssid, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${ap.rssi} dBm", fontFamily = FontFamily.Monospace)
                Text(
                    String.format(Locale.ROOT, "w %.1f %%  (%.2e)", ap.normalizedWeight * 100, ap.weight),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
        }
        LinearProgressIndicator(
            progress = { ap.normalizedWeight.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Spacer(Modifier.width(1.dp).height(6.dp))
        HorizontalDivider()
    }
}

// ───────────────────────────── Helpers ─────────────────────────────

private fun openDeveloperOptions(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(ctx, "Entwickleroptionen nicht aktiviert: 7× auf „Build-Nummer“ tippen", Toast.LENGTH_LONG).show()
        ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun isLocationEnabled(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
    else @Suppress("DEPRECATION") (Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, 0) != 0)
}

private fun isWifiScanPossible(ctx: Context): Boolean {
    val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    return wm.isWifiEnabled || wm.isScanAlwaysAvailable
}
