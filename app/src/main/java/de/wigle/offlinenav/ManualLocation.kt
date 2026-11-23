package de.wigle.offlinenav

import android.content.Context
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/** Manuell gewählter Test-Standort, der statt der WLAN-Schätzung eingespeist wird. */
data class ManualLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 10f,
) {
    fun validate(): String? = when {
        latitude.isNaN() || latitude !in -90.0..90.0 -> "Breitengrad muss zwischen −90 und 90 liegen"
        longitude.isNaN() || longitude !in -180.0..180.0 -> "Längengrad muss zwischen −180 und 180 liegen"
        accuracyMeters.isNaN() || accuracyMeters <= 0f -> "Genauigkeit muss > 0 sein"
        else -> null
    }

    /** Verschiebt um [northM] / [eastM] Meter (flache Näherung, für Testschritte ausreichend). */
    fun offset(northM: Double, eastM: Double): ManualLocation {
        val dLat = northM / METERS_PER_DEG_LAT
        val dLon = eastM / (METERS_PER_DEG_LAT * cos(latitude * PI / 180)).coerceAtLeast(1.0)
        var lon = longitude + dLon
        if (lon > 180) lon -= 360
        if (lon < -180) lon += 360
        return copy(latitude = (latitude + dLat).coerceIn(-90.0, 90.0), longitude = lon)
    }

    companion object {
        private const val METERS_PER_DEG_LAT = 111_320.0

        private val GEO_URI = Regex("""^geo:\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val GEO_QUERY = Regex("""[?&]q=\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)""")
        private val AT_PATH = Regex("""@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)""")
        private val OSM_PARAMS = Regex("""mlat=(-?\d+(?:\.\d+)?).*?mlon=(-?\d+(?:\.\d+)?)""")
        private val OSM_HASH = Regex("""#map=\d+/(-?\d+(?:\.\d+)?)/(-?\d+(?:\.\d+)?)""")
        private val PLAIN = Regex("""^\s*(-?\d+(?:[.,]\d+)?)\s*[,;\s]\s*(-?\d+(?:[.,]\d+)?)\s*$""")

        /**
         * Erkennt Koordinaten aus Freitext:
         *  „52.5163, 13.3777“, „52,5163 13,3777“, „52.5163;13.3777“,
         *  geo:-URIs, Google-Maps-Links (…/@lat,lon,…) und OSM-Links (mlat/mlon oder #map=z/lat/lon).
         */
        fun parse(text: String): Pair<Double, Double>? {
            val t = text.trim()
            val m = GEO_URI.find(t)?.takeIf { it.groupValues[1] != "0" || it.groupValues[2] != "0" }
                ?: GEO_QUERY.find(t) ?: AT_PATH.find(t) ?: OSM_PARAMS.find(t) ?: OSM_HASH.find(t)
            val (a, b) = if (m != null) {
                m.groupValues[1] to m.groupValues[2]
            } else {
                // „52,5163 13,3777“ (Dezimalkomma) vs. „52.5,13.4“ (Komma als Trenner)
                val p = PLAIN.find(t) ?: return null
                p.groupValues[1] to p.groupValues[2]
            }
            val lat = a.replace(',', '.').toDoubleOrNull() ?: return null
            val lon = b.replace(',', '.').toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return lat to lon
        }

        fun format(v: Double): String = String.format(Locale.ROOT, "%.6f", v)
    }
}

/** Merkt sich die zuletzt eingegebenen Test-Koordinaten. */
class ManualLocationStore(context: Context) {
    private val prefs = context.getSharedPreferences("manual_location", Context.MODE_PRIVATE)

    var lat: String
        get() = prefs.getString("lat", "") ?: ""
        set(v) = prefs.edit().putString("lat", v).apply()
    var lon: String
        get() = prefs.getString("lon", "") ?: ""
        set(v) = prefs.edit().putString("lon", v).apply()
    var accuracy: String
        get() = prefs.getString("acc", "10") ?: "10"
        set(v) = prefs.edit().putString("acc", v).apply()
    var stepMeters: String
        get() = prefs.getString("step", "50") ?: "50"
        set(v) = prefs.edit().putString("step", v).apply()
}
