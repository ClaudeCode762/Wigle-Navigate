package de.wigle.offlinenav

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Ein Ergebnis aus dem WLAN-Scan. */
data class ScanSample(val bssid: String, val ssid: String, val rssi: Int)

/** Ein für die Berechnung genutzter, bekannter AP. */
data class UsedAccessPoint(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val latitude: Double,
    val longitude: Double,
    val weight: Double,           // roh: 10^(rssi/10)
    val normalizedWeight: Double, // Anteil am Gesamtgewicht (0..1)
)

data class PositionEstimate(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val used: List<UsedAccessPoint>,
    val rejectedOutliers: Int,
)

/**
 * Weighted-Centroid-Positionsschätzung.
 *
 *  weight = 10^(rssi/10)   (lineare Leistung in mW → starke APs dominieren deutlich)
 *  lat = Σ(lat·w)/Σw ; lon = Σ(lon·w)/Σw
 *
 * Zusätzlich:
 *  - nur die N stärksten Treffer
 *  - Ausreißer-Filter: APs, die > [outlierMeters] vom Median-Zentrum entfernt sind
 *    (umgezogene Router, falsche WiGLE-Koordinaten), werden verworfen.
 */
class LocationCalculator(
    private val maxAps: Int = 12,
    private val minRssi: Int = -95,
    private val outlierMeters: Double = 500.0,
) {

    fun estimate(known: List<Pair<ScanSample, AccessPointEntity>>): PositionEstimate? {
        val candidates = known
            .filter { it.first.rssi >= minRssi }
            .sortedByDescending { it.first.rssi }
            .take(maxAps)
        if (candidates.isEmpty()) return null

        // 1) Ausreißer entfernen (nur sinnvoll ab 3 APs)
        var kept = candidates
        var rejected = 0
        if (candidates.size >= 3) {
            // Ungewichteter Median: ein einzelner, sehr starker (umgezogener) AP darf das Zentrum nicht kapern.
            val medLat = median(candidates.map { it.second.latitude })
            val medLon = median(candidates.map { it.second.longitude })
            val filtered = candidates.filter {
                haversine(medLat, medLon, it.second.latitude, it.second.longitude) <= outlierMeters
            }
            if (filtered.isNotEmpty()) {
                rejected = candidates.size - filtered.size
                kept = filtered
            }
        }

        // 2) Weighted Centroid
        var sumW = 0.0
        var sumLat = 0.0
        var sumLon = 0.0
        for ((scan, ap) in kept) {
            val w = weight(scan.rssi)
            sumW += w
            sumLat += ap.latitude * w
            sumLon += ap.longitude * w
        }
        if (sumW <= 0.0) return null
        val lat = sumLat / sumW
        val lon = sumLon / sumW

        val used = kept.map { (scan, ap) ->
            val w = weight(scan.rssi)
            UsedAccessPoint(
                bssid = ap.bssid,
                ssid = ap.ssid.ifEmpty { scan.ssid },
                rssi = scan.rssi,
                latitude = ap.latitude,
                longitude = ap.longitude,
                weight = w,
                normalizedWeight = w / sumW,
            )
        }

        return PositionEstimate(lat, lon, accuracy(used, lat, lon), used, rejected)
    }

    /**
     * Genauigkeit (m), Heuristik:
     *  - Distanz zum stärksten AP per Log-Distance-Pfadverlust (−40 dBm @1 m, n = 3)
     *  - + Grundfehler der WiGLE-Koordinaten (~20 m)
     *  - geteilt durch √(Anzahl APs) (mehr Treffer → bessere Mittelung)
     *  - mind. gewichtete Streuung der APs um die Schätzung
     */
    private fun accuracy(used: List<UsedAccessPoint>, lat: Double, lon: Double): Float {
        val maxRssi = used.maxOf { it.rssi }
        val distStrongest = 10.0.pow((-40.0 - maxRssi) / (10.0 * 3.0))
        val base = (distStrongest + WIGLE_BASE_ERROR_M) / sqrt(used.size.toDouble())
        val spread = used.sumOf { it.normalizedWeight * haversine(lat, lon, it.latitude, it.longitude) }
        val acc = max(base, spread)
        return min(max(acc, 5.0), 2_000.0).toFloat()
    }

    companion object {
        private const val WIGLE_BASE_ERROR_M = 20.0
        private const val EARTH_R = 6_371_000.0

        fun weight(rssi: Int): Double = 10.0.pow(rssi / 10.0)

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat2 - lat1) * PI / 180
            val dLon = (lon2 - lon1) * PI / 180
            val a = sin(dLat / 2).pow(2) +
                cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2).pow(2)
            return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a))
        }

        private fun median(values: List<Double>): Double {
            val s = values.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }
    }
}
