package de.wigle.offlinenav

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

data class ImportResult(
    val linesRead: Long,
    val imported: Long,
    val skippedNonWifi: Long,
    val skippedInvalid: Long,
)

/**
 * Importiert WiGLE-CSV-Exporte (auch .csv.gz).
 *
 * Format:
 *   WigleWifi-1.4,appRelease=...,model=...     <- optionale Pre-Header-Zeile
 *   MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type
 *   ...
 * Spalten werden über den Header gemappt, zusätzliche Spalten (Frequency, RCOIs, MfgrId ...) sind egal.
 */
class CsvImporter(private val context: Context) {

    private val db = AppDatabase.get(context)
    private val dao = db.accessPointDao()

    suspend fun import(uri: Uri, onProgress: (Long) -> Unit = {}): ImportResult =
        withContext(Dispatchers.IO) {
            val raw = context.contentResolver.openInputStream(uri)
                ?: error("Datei konnte nicht geöffnet werden")
            BufferedReader(InputStreamReader(openMaybeGzip(raw), Charsets.UTF_8), 1 shl 16).use { reader ->
                parse(reader, onProgress)
            }
        }

    private fun openMaybeGzip(input: InputStream): InputStream {
        val buffered = BufferedInputStream(input, 1 shl 16)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 1 shl 16) else buffered
    }

    private suspend fun parse(reader: BufferedReader, onProgress: (Long) -> Unit): ImportResult {
        var lines = 0L
        var imported = 0L
        var nonWifi = 0L
        var invalid = 0L

        // Header suchen (Pre-Header "WigleWifi-1.x" überspringen)
        var header: List<String>? = null
        while (header == null) {
            val line = reader.readLine() ?: error("Kein WiGLE-Header (MAC,SSID,...) gefunden")
            val cols = parseCsvLine(line.removePrefix("\uFEFF"))
            if (cols.any { it.trim().equals("MAC", ignoreCase = true) }) header = cols.map { it.trim() }
        }
        val idx = header.withIndex().associate { (i, n) -> n.lowercase() to i }
        val iMac = idx["mac"] ?: error("Spalte MAC fehlt")
        val iSsid = idx["ssid"]
        val iLat = idx["currentlatitude"] ?: error("Spalte CurrentLatitude fehlt")
        val iLon = idx["currentlongitude"] ?: error("Spalte CurrentLongitude fehlt")
        val iType = idx["type"]

        val batch = ArrayList<AccessPointEntity>(BATCH)
        suspend fun flush() {
            if (batch.isEmpty()) return
            db.withTransaction { dao.insertAll(batch) }
            imported += batch.size
            batch.clear()
            onProgress(imported)
        }

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            lines++
            val c = parseCsvLine(line)
            val type = iType?.let { c.getOrNull(it)?.trim() } ?: "WIFI"
            if (!type.equals("WIFI", ignoreCase = true)) { nonWifi++; continue }

            val mac = c.getOrNull(iMac)?.trim()?.lowercase()
            val lat = c.getOrNull(iLat)?.trim()?.toDoubleOrNull()
            val lon = c.getOrNull(iLon)?.trim()?.toDoubleOrNull()
            if (mac == null || !MAC_REGEX.matches(mac) || lat == null || lon == null ||
                (lat == 0.0 && lon == 0.0) || lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
                lat.isNaN() || lon.isNaN()
            ) { invalid++; continue }

            batch += AccessPointEntity(
                bssid = mac,
                ssid = iSsid?.let { c.getOrNull(it) } ?: "",
                latitude = lat,
                longitude = lon,
            )
            if (batch.size >= BATCH) {
                coroutineContext.ensureActive()
                flush()
            }
        }
        flush()
        return ImportResult(lines, imported, nonWifi, invalid)
    }

    companion object {
        private const val BATCH = 5_000
        private val MAC_REGEX = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

        /** RFC-4180-CSV-Zeile parsen (SSIDs können Kommas und Anführungszeichen enthalten). */
        fun parseCsvLine(line: String): List<String> {
            val out = ArrayList<String>(14)
            val sb = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    ch == '"' -> inQuotes = !inQuotes
                    ch == ',' && !inQuotes -> { out += sb.toString(); sb.setLength(0) }
                    else -> sb.append(ch)
                }
                i++
            }
            out += sb.toString()
            return out
        }
    }
}
