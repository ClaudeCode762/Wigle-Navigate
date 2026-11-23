package de.wigle.offlinenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCalculatorTest {
    private fun ap(b: String, lat: Double, lon: Double) = AccessPointEntity(bssid = b, ssid = b, latitude = lat, longitude = lon)
    private fun s(b: String, rssi: Int) = ScanSample(b, b, rssi)

    @Test fun emptyReturnsNull() = assertNull(LocationCalculator().estimate(emptyList()))

    @Test fun singleApIsItsPosition() {
        val e = LocationCalculator().estimate(listOf(s("a", -60) to ap("a", 48.5, 9.2)))!!
        assertEquals(48.5, e.latitude, 1e-9); assertEquals(9.2, e.longitude, 1e-9)
    }

    @Test fun strongerApDominates() {
        val e = LocationCalculator().estimate(listOf(
            s("a", -50) to ap("a", 48.0000, 9.0000),
            s("b", -80) to ap("b", 48.0010, 9.0010),
        ))!!
        // 30 dB Unterschied → Gewicht 1000:1
        assertTrue(LocationCalculator.haversine(e.latitude, e.longitude, 48.0, 9.0) < 1.0)
    }

    @Test fun outlierRejected() {
        val e = LocationCalculator().estimate(listOf(
            s("a", -60) to ap("a", 48.0000, 9.0000),
            s("b", -62) to ap("b", 48.0002, 9.0002),
            s("c", -40) to ap("c", 52.5, 13.4), // umgezogener Router
            s("d", -65) to ap("d", 48.0001, 9.0003),
        ))!!
        assertEquals(1, e.rejectedOutliers)
        assertTrue(LocationCalculator.haversine(e.latitude, e.longitude, 48.0001, 9.0001) < 50)
    }

    @Test fun csvQuotedSsid() {
        val c = CsvImporter.parseCsvLine("aa:bb:cc:dd:ee:ff,\"My, \"\"Net\"\"\",[WPA2],2024-01-01,6,-70,48.1,9.1,300,5,WIFI")
        assertEquals("My, \"Net\"", c[1]); assertEquals("WIFI", c[10]); assertEquals(11, c.size)
    }
}
