package de.wigle.offlinenav

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class BoundingBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    fun validate(): String? = when {
        minLat !in -90.0..90.0 || maxLat !in -90.0..90.0 -> "Latitude muss zwischen -90 und 90 liegen"
        minLon !in -180.0..180.0 || maxLon !in -180.0..180.0 -> "Longitude muss zwischen -180 und 180 liegen"
        minLat >= maxLat -> "Min Lat muss kleiner als Max Lat sein"
        minLon >= maxLon -> "Min Lon muss kleiner als Max Lon sein"
        else -> null
    }
}

data class DownloadProgress(val page: Int, val received: Long, val total: Long?)

data class DownloadResult(val pages: Int, val received: Long, val stored: Long, val total: Long?, val complete: Boolean)

class WigleApiException(message: String) : IOException(message)

/** Persistiert API-Name/Token und letzte Bounding Box (app-privat, kein Backup). */
class WigleCredentialsStore(context: Context) {
    private val prefs = context.getSharedPreferences("wigle_api", Context.MODE_PRIVATE)

    var apiName: String
        get() = prefs.getString("api_name", "") ?: ""
        set(v) = prefs.edit().putString("api_name", v.trim()).apply()
    var apiToken: String
        get() = prefs.getString("api_token", "") ?: ""
        set(v) = prefs.edit().putString("api_token", v.trim()).apply()

    var bbox: List<String>
        get() = listOf("min_lat", "max_lat", "min_lon", "max_lon").map { prefs.getString(it, "") ?: "" }
        set(v) {
            val e = prefs.edit()
            listOf("min_lat", "max_lat", "min_lon", "max_lon").zip(v).forEach { (k, x) -> e.putString(k, x) }
            e.apply()
        }
}

/**
 * WiGLE API v2 – `GET /api/v2/network/search` mit Basic-Auth (API Name : API Token).
 * Paginierung über `searchAfter`. Ergebnisse werden direkt in die Room-DB geschrieben.
 */
class WigleApiClient(context: Context) {
    private val db = AppDatabase.get(context)
    private val dao = db.accessPointDao()

    suspend fun download(
        apiName: String,
        apiToken: String,
        box: BoundingBox,
        maxPages: Int,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        require(apiName.isNotBlank() && apiToken.isNotBlank()) { "API Name und Token angeben" }
        box.validate()?.let { throw IllegalArgumentException(it) }
        val auth = "Basic " + Base64.encodeToString("$apiName:$apiToken".toByteArray(), Base64.NO_WRAP)

        var searchAfter: String? = null
        var pages = 0
        var received = 0L
        var stored = 0L
        var total: Long? = null
        var complete = false

        while (pages < maxPages) {
            coroutineContext.ensureActive()
            val json = request(buildUrl(box, searchAfter), auth)
            if (!json.optBoolean("success", false)) {
                throw WigleApiException(json.optString("message", "WiGLE meldet Fehler"))
            }
            if (json.has("totalResults")) total = json.optLong("totalResults")
            val results = json.optJSONArray("results")
            val n = results?.length() ?: 0
            pages++
            received += n

            val batch = ArrayList<AccessPointEntity>(n)
            for (i in 0 until n) {
                val r = results!!.getJSONObject(i)
                val mac = r.optString("netid").trim().lowercase(Locale.ROOT)
                val lat = r.optDouble("trilat", Double.NaN)
                val lon = r.optDouble("trilong", Double.NaN)
                if (!MAC_REGEX.matches(mac) || lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) continue
                val ssid = if (r.isNull("ssid")) "" else r.optString("ssid")
                batch += AccessPointEntity(bssid = mac, ssid = ssid, latitude = lat, longitude = lon)
            }
            if (batch.isNotEmpty()) {
                db.withTransaction { dao.insertAll(batch) }
                stored += batch.size
            }
            onProgress(DownloadProgress(pages, received, total))

            searchAfter = json.opt("searchAfter")?.takeIf { it != JSONObject.NULL }?.toString()
            if (n == 0 || searchAfter.isNullOrEmpty() || (total != null && received >= total)) {
                complete = true
                break
            }
        }
        DownloadResult(pages, received, stored, total, complete)
    }

    private fun buildUrl(b: BoundingBox, searchAfter: String?): URL {
        fun f(d: Double) = String.format(Locale.ROOT, "%.6f", d)
        val q = buildString {
            append("latrange1=").append(f(b.minLat))
            append("&latrange2=").append(f(b.maxLat))
            append("&longrange1=").append(f(b.minLon))
            append("&longrange2=").append(f(b.maxLon))
            append("&resultsPerPage=").append(RESULTS_PER_PAGE)
            if (searchAfter != null) append("&searchAfter=").append(URLEncoder.encode(searchAfter, "UTF-8"))
        }
        return URL("$BASE_URL?$q")
    }

    private fun request(url: URL, auth: String): JSONObject {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", auth)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WiGLE-Offline-Navigator/1.0")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val apiMsg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
            when (code) {
                in 200..299 -> return JSONObject(body)
                401, 403 -> throw WigleApiException("Authentifizierung fehlgeschlagen ($code) – API Name/Token prüfen. $apiMsg")
                429 -> throw WigleApiException("Tageslimit der WiGLE-API erreicht (429). Bereits geladene Seiten sind gespeichert. $apiMsg")
                else -> throw WigleApiException("HTTP $code: ${apiMsg.ifEmpty { body.take(200) }}")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://api.wigle.net/api/v2/network/search"
        const val RESULTS_PER_PAGE = 100
        private val MAC_REGEX = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    }
}
