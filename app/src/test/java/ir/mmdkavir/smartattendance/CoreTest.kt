package ir.mmdkavir.smartattendance

import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import ir.mmdkavir.smartattendance.net.Payload
import ir.mmdkavir.smartattendance.util.Geo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    private val record = AttendanceRecord(
        eventId = "v1",
        arrivalMillis = 0L,
        zoneId = "Asia/Tehran",
        date = "2026-11-07",
        time = "08:32:15",
        workplace = "Main Office",
        lat = 35.6892,
        lng = 51.3890,
        accuracy = 12f,
        status = "ARRIVED"
    )

    @Test
    fun distanceBetweenSamePointIsZero() {
        assertEquals(0.0, Geo.distanceMeters(35.7, 51.4, 35.7, 51.4), 0.001)
    }

    @Test
    fun oneDegreeOfLatitudeIsAbout111Km() {
        assertEquals(111_195.0, Geo.distanceMeters(0.0, 0.0, 1.0, 0.0), 500.0)
    }

    @Test
    fun arrivalPayloadCarriesConfiguredValues() {
        val settings = AppSettings(name = "Kavir", employeeId = "E-42", workplace = "Main Office")
        val p = Payload.arrival(settings, record)
        assertEquals("arrival", p["kind"])
        assertEquals("en", p["lang"])
        assertEquals("Kavir", p["name"])
        assertEquals("E-42", p["employee_id"])
        assertEquals("Main Office", p["workplace"])
        assertEquals("2026-11-07", p["date"])
        assertEquals("08:32:15", p["time"])
        assertEquals("35.6892", p["lat"])
        assertEquals("51.389", p["lng"])
    }

    @Test
    fun payloadSurvivesJsonRoundTrip() {
        val p = Payload.arrival(AppSettings(name = "نام \"x\"", employeeId = ""), record)
        assertEquals(p, Payload.fromJson(Payload.toJson(p)))
    }
}
