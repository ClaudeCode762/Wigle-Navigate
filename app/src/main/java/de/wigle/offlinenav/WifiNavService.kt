package de.wigle.offlinenav

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** Live-Zustand, den die UI beobachtet. */
data class NavState(
    val running: Boolean = false,
    val mockActive: Boolean = false,
    val mockError: String? = null,
    val scanCount: Long = 0,
    val lastScanAt: Long = 0,
    val visibleNetworks: Int = 0,
    val knownNetworks: Int = 0,
    val unknownNetworks: Int = 0,
    val throttled: Boolean = false,
    val estimate: PositionEstimate? = null,
    val estimateAt: Long = 0,
    val message: String? = null,
)

/**
 * Foreground-Service:
 *  WLAN-Scan → Abgleich mit Room-DB → Weighted Centroid → Mock-Location (GPS + Network Provider).
 */
class WifiNavService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var wifi: WifiManager
    private lateinit var dao: AccessPointDao
    private lateinit var mock: MockLocationPublisher
    private val calculator = LocationCalculator()
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiverRegistered = false

    /** Signal „neue Scan-Ergebnisse verfügbar“, konfliktfrei zusammengefasst. */
    private val scanTrigger = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true)
            _state.update { it.copy(throttled = !updated) }
            scanTrigger.tryEmit(Unit)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        dao = AppDatabase.get(this).accessPointDao()
        mock = MockLocationPublisher(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (_state.value.running) return START_STICKY

        if (!hasLocationPermission(this)) {
            _state.update { it.copy(message = "Standort-Berechtigung fehlt") }
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification("Starte WLAN-Scan …"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            _state.update { it.copy(message = "Foreground-Service konnte nicht starten: ${e.message}") }
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        val mockErr = mock.enable()
        _state.value = NavState(running = true, mockActive = mockErr == null, mockError = mockErr)

        ContextCompat.registerReceiver(
            this, scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true

        scope.launch { scanLoop() }
        scope.launch { processLoop() }
        scope.launch { republishLoop() }
        scope.launch {
            _manual.collect { m ->
                m?.let { publish(it.latitude, it.longitude, it.accuracyMeters) }
                updateNotification()
            }
        }
        return START_STICKY
    }

    /** Fordert periodisch Scans an. Bei Throttling liefert getScanResults() trotzdem den Cache. */
    @Suppress("DEPRECATION")
    private suspend fun scanLoop() {
        while (scope.isActive) {
            val accepted = try { wifi.startScan() } catch (e: SecurityException) { false }
            if (!accepted) {
                _state.update { it.copy(throttled = true) }
                scanTrigger.tryEmit(Unit) // gecachte Ergebnisse trotzdem auswerten
            }
            delay(SCAN_INTERVAL_MS)
        }
    }

    private suspend fun processLoop() {
        scanTrigger.collect {
            try { processScanResults() } catch (e: Exception) { Log.e(TAG, "process failed", e) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun processScanResults() {
        val results = try { wifi.scanResults } catch (e: SecurityException) {
            _state.update { it.copy(message = "Keine Berechtigung für Scan-Ergebnisse") }
            return
        }
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000
        val fresh = results.filter { nowUs - it.timestamp <= MAX_RESULT_AGE_US }

        val samples = fresh.mapNotNull { r ->
            @Suppress("DEPRECATION")
            val bssid = r.BSSID?.lowercase(Locale.ROOT) ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val ssid = r.SSID ?: ""
            ScanSample(bssid, ssid, r.level)
        }.groupBy { it.bssid }.map { (_, v) -> v.maxBy { it.rssi } }

        val known = dao.findByBssids(samples.map { it.bssid }).associateBy { it.bssid }
        val matches = samples.mapNotNull { s -> known[s.bssid]?.let { s to it } }
        val estimate = calculator.estimate(matches)
        val now = System.currentTimeMillis()

        _state.update {
            it.copy(
                scanCount = it.scanCount + 1,
                lastScanAt = now,
                visibleNetworks = samples.size,
                knownNetworks = matches.size,
                unknownNetworks = samples.size - matches.size,
                estimate = estimate ?: it.estimate,
                estimateAt = if (estimate != null) now else it.estimateAt,
                message = if (samples.isEmpty()) "Keine WLANs sichtbar (Standort/WLAN aktiviert?)"
                else if (estimate == null) "Kein bekannter Access Point in Reichweite" else null,
            )
        }
        if (_manual.value == null) estimate?.let { publish(it.latitude, it.longitude, it.accuracyMeters) }
        updateNotification()
    }

    /**
     * Wiederholt die letzte Position jede Sekunde mit frischem Zeitstempel, damit
     * Navigations-Apps den „GPS“-Fix zwischen (gedrosselten) Scans nicht verwerfen.
     */
    private suspend fun republishLoop() {
        while (scope.isActive) {
            delay(1_000)
            val m = _manual.value
            if (m != null) {
                publish(m.latitude, m.longitude, m.accuracyMeters)
                continue
            }
            val s = _state.value
            val est = s.estimate ?: continue
            if (System.currentTimeMillis() - s.estimateAt <= MAX_REPUBLISH_AGE_MS) {
                publish(est.latitude, est.longitude, est.accuracyMeters)
            }
        }
    }

    @Synchronized
    private fun publish(lat: Double, lon: Double, accuracy: Float) {
        val err = mock.push(lat, lon, accuracy)
        if (err != _state.value.mockError) {
            _state.update { it.copy(mockError = err, mockActive = err == null) }
        }
    }

    private fun updateNotification() {
        val s = _state.value
        val m = _manual.value
        val text = if (m != null) String.format(
            Locale.ROOT, "TEST-STANDORT %.6f, %.6f ±%.0f m", m.latitude, m.longitude, m.accuracyMeters
        ) else s.estimate?.let {
            String.format(Locale.ROOT, "%.6f, %.6f ±%.0f m · %d/%d bekannt",
                it.latitude, it.longitude, it.accuracyMeters, s.knownNetworks, s.visibleNetworks)
        } ?: "Suche bekannte WLANs … (${s.visibleNetworks} sichtbar)"
        val nm = getSystemService(NotificationManager::class.java)
        try { nm.notify(NOTIF_ID, buildNotification(text)) } catch (_: SecurityException) { }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "WLAN-Navigation", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WifiNavService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("WiGLE Offline Navigator aktiv")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stopp", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WigleNav::scan").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (receiverRegistered) {
            try { unregisterReceiver(scanReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        mock.disable()
        _manual.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        _state.update { it.copy(running = false, mockActive = false) }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WifiNavService"
        private const val CHANNEL_ID = "wifi_nav"
        private const val NOTIF_ID = 42
        const val ACTION_START = "de.wigle.offlinenav.START"
        const val ACTION_STOP = "de.wigle.offlinenav.STOP"
        private const val SCAN_INTERVAL_MS = 4_000L
        private const val MAX_RESULT_AGE_US = 30_000_000L   // 30 s
        private const val MAX_REPUBLISH_AGE_MS = 60_000L

        private val _state = MutableStateFlow(NavState())
        val state: StateFlow<NavState> = _state.asStateFlow()

        /** Manueller Test-Standort; überschreibt die WLAN-Schätzung, solange gesetzt. */
        private val _manual = MutableStateFlow<ManualLocation?>(null)
        val manual: StateFlow<ManualLocation?> = _manual.asStateFlow()

        /** Setzt den Test-Standort und startet den Service bei Bedarf (Berechtigung muss vorliegen). */
        fun setManualLocation(context: Context, loc: ManualLocation) {
            _manual.value = loc
            if (!_state.value.running) start(context)
        }

        /** Zurück zur WLAN-basierten Position. */
        fun clearManualLocation() {
            _manual.value = null
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, WifiNavService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WifiNavService::class.java))
        }

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Speist Positionen über LocationManager-Testprovider systemweit ein.
 * Funktioniert nur, wenn diese App in den Entwickleroptionen als
 * „App für simulierte Standorte“ ausgewählt ist.
 */
class MockLocationPublisher(context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    private val active = mutableSetOf<String>()

    /** @return Fehlermeldung oder null bei Erfolg. */
    @Suppress("DEPRECATION")
    @SuppressLint("WrongConstant") // Criteria.POWER_LOW/ACCURACY_FINE == ProviderProperties-Werte (API < 31)
    fun enable(): String? {
        var error: String? = null
        for (p in providers) {
            try {
                try { lm.removeTestProvider(p) } catch (_: Exception) { }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lm.addTestProvider(
                        p,
                        ProviderProperties.Builder()
                            .setHasNetworkRequirement(p == LocationManager.NETWORK_PROVIDER)
                            .setHasSatelliteRequirement(p == LocationManager.GPS_PROVIDER)
                            .setHasAltitudeSupport(true)
                            .setHasSpeedSupport(true)
                            .setHasBearingSupport(true)
                            .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                            .setAccuracy(ProviderProperties.ACCURACY_FINE)
                            .build()
                    )
                } else {
                    lm.addTestProvider(
                        p,
                        p == LocationManager.NETWORK_PROVIDER, // requiresNetwork
                        p == LocationManager.GPS_PROVIDER,     // requiresSatellite
                        false, false,
                        true, true, true,
                        Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                    )
                }
                lm.setTestProviderEnabled(p, true)
                active += p
            } catch (e: SecurityException) {
                error = "Nicht als Mock-Location-App gewählt (Entwickleroptionen)"
            } catch (e: Exception) {
                if (p == LocationManager.GPS_PROVIDER) error = "Testprovider $p: ${e.message}"
                Log.w("MockLocation", "addTestProvider $p failed", e)
            }
        }
        return if (active.contains(LocationManager.GPS_PROVIDER)) null else error
    }

    fun push(lat: Double, lon: Double, accuracy: Float): String? {
        if (active.isEmpty()) {
            enable()?.let { return it }
        }
        var err: String? = null
        for (p in active.toList()) {
            val loc = Location(p).apply {
                latitude = lat
                longitude = lon
                this.accuracy = accuracy
                altitude = 0.0
                speed = 0f
                bearing = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                verticalAccuracyMeters = 50f
                speedAccuracyMetersPerSecond = 1f
                bearingAccuracyDegrees = 180f
            }
            try {
                lm.setTestProviderLocation(p, loc)
            } catch (e: SecurityException) {
                active.clear()
                err = "Mock-Location-Rechte entzogen"
            } catch (e: Exception) {
                err = "setTestProviderLocation: ${e.message}"
            }
        }
        return err
    }

    fun disable() {
        for (p in active) {
            try { lm.setTestProviderEnabled(p, false) } catch (_: Exception) { }
            try { lm.removeTestProvider(p) } catch (_: Exception) { }
        }
        active.clear()
    }

    companion object {
        /** Prüft, ob diese App als „App für simulierte Standorte“ ausgewählt ist. */
        fun isMockLocationApp(context: Context): Boolean {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
                } else {
                    @Suppress("DEPRECATION")
                    ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
                }
            } catch (_: Exception) { AppOpsManager.MODE_ERRORED }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
