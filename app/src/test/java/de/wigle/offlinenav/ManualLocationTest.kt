package de.wigle.offlinenav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManualLocationTest {
    private fun assertParsed(text: String, lat: Double, lon: Double) {
        val p = ManualLocation.parse(text)
        assertNotNull(text, p)
        assertEquals(text, lat, p!!.first, 1e-9)
        assertEquals(text, lon, p.second, 1e-9)
    }

    @Test fun parsesPlainFormats() {
        assertParsed("52.516275, 13.377704", 52.516275, 13.377704)
        assertParsed("52.516275,13.377704", 52.516275, 13.377704)
        assertParsed("52,516275 13,377704", 52.516275, 13.377704)
        assertParsed("52.5;13.4", 52.5, 13.4)
        assertParsed("-33.8568 151.2153", -33.8568, 151.2153)
        assertParsed("52,13", 52.0, 13.0)
    }

    @Test fun parsesLinks() {
        assertParsed("geo:52.5163,13.3777?z=17", 52.5163, 13.3777)
        assertParsed("geo:0,0?q=48.1371,11.5761(Marienplatz)", 48.1371, 11.5761)
        assertParsed("https://www.google.com/maps/@50.9412,6.9582,17z", 50.9412, 6.9582)
        assertParsed("https://www.openstreetmap.org/?mlat=53.5503&mlon=10.0006#map=17/53.5503/10.0006", 53.5503, 10.0006)
        assertParsed("https://www.openstreetmap.org/#map=17/50.1105/8.6822", 50.1105, 8.6822)
    }

    @Test fun rejectsGarbageAndOutOfRange() {
        assertNull(ManualLocation.parse(""))
        assertNull(ManualLocation.parse("hallo welt"))
        assertNull(ManualLocation.parse("95.0, 13.0"))
        assertNull(ManualLocation.parse("52.0, 200.0"))
    }

    @Test fun validate() {
        assertNull(ManualLocation(52.0, 13.0, 10f).validate())
        assertNotNull(ManualLocation(91.0, 13.0).validate())
        assertNotNull(ManualLocation(52.0, -181.0).validate())
        assertNotNull(ManualLocation(52.0, 13.0, 0f).validate())
    }

    @Test fun offsetMovesByMeters() {
        val start = ManualLocation(52.0, 13.0)
        val north = start.offset(100.0, 0.0)
        assertEquals(100.0, LocationCalculator.haversine(52.0, 13.0, north.latitude, north.longitude), 0.5)
        val east = start.offset(0.0, 100.0)
        assertEquals(100.0, LocationCalculator.haversine(52.0, 13.0, east.latitude, east.longitude), 0.5)
        assertEquals(52.0, east.latitude, 1e-12)
    }

    @Test fun offsetWrapsAntimeridian() {
        val m = ManualLocation(0.0, 179.9999).offset(0.0, 1_000.0)
        assertEquals(true, m.longitude in -180.0..-179.0)
    }
}
