package ir.mmdkavir.smartattendance

import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import ir.mmdkavir.smartattendance.net.Payload
import ir.mmdkavir.smartattendance.util.ExitGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleExitTest {
    private val record = AttendanceRecord(
        eventId = "v1", arrivalMillis = 1L, zoneId = "Asia/Tehran", date = "2026-11-14", time = "08:00:00",
        workplace = "Office", lat = 35.0, lng = 51.0, accuracy = 10f, status = "ARRIVED"
    )
    private val s = AppSettings(name = "Owner", employeeId = "7")

    @Test
    fun arrivalWithoutPeopleKeepsSettingsName() {
        val p = Payload.arrival(s, record)
        assertEquals("Owner", p["name"])
        assertEquals("7", p["employee_id"])
    }

    @Test
    fun arrivalListsEveryEnabledPerson() {
        val p = Payload.arrival(s, record, listOf("Ali", "Sara"))
        assertEquals("Ali, Sara", p["name"])
        assertEquals("", p["employee_id"])
    }

    @Test
    fun exitPayloadHasNameDateAndTime() {
        val p = Payload.exit(s.copy(msgLang = "fa"), 1_763_000_000_000L)
        assertEquals("exit", p["kind"])
        assertEquals("fa", p["lang"])
        assertEquals("Owner", p["name"])
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}").matches(p["date"]!!))
        assertTrue(Regex("\\d{2}:\\d{2}:\\d{2}").matches(p["time"]!!))
    }

    @Test
    fun exitCarriesOnlyTheGivenActivePeople() {
        assertEquals("Ali|Reza", Payload.exit(s, 1_763_000_000_000L, listOf("Ali", "Reza"))["workplace"])
        assertEquals("", Payload.exit(s, 1_763_000_000_000L)["workplace"])
        assertEquals("A B", Payload.exit(s, 1_763_000_000_000L, listOf("A|B"))["workplace"])
    }

    @Test
    fun repeatedExitTapsAreIgnored() {
        val t0 = 1_000_000L
        assertFalse(ExitGuard.isDuplicate(t0, 0L, false))
        assertTrue(ExitGuard.isDuplicate(t0 + 5_000, t0, false))
        assertTrue(ExitGuard.isDuplicate(t0 + ExitGuard.WINDOW_MS + 1, t0, true))
        assertFalse(ExitGuard.isDuplicate(t0 + ExitGuard.WINDOW_MS + 1, t0, false))
    }
}
