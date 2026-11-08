package ir.mmdkavir.smartattendance

import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import ir.mmdkavir.smartattendance.net.Messages
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
    fun arrivalMessageUsesConfiguredValues() {
        val settings = AppSettings(name = "Kavir", employeeId = "E-42", workplace = "Main Office")
        val text = Messages.arrival(settings, record)
        assertTrue(text.startsWith("WORK ATTENDANCE"))
        assertTrue(text.contains("Name: Kavir"))
        assertTrue(text.contains("Employee ID: E-42"))
        assertTrue(text.contains("Workplace: Main Office"))
        assertTrue(text.contains("Date: 2026-11-07"))
        assertTrue(text.contains("Arrival Time: 08:32:15"))
        assertTrue(text.endsWith("Status: ARRIVED"))
    }

    @Test
    fun employeeIdLineIsOmittedWhenBlank() {
        val settings = AppSettings(name = "Kavir", employeeId = "")
        assertFalse(Messages.arrival(settings, record).contains("Employee ID"))
    }
}
