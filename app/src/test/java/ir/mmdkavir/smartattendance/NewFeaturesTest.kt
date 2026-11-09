package ir.mmdkavir.smartattendance

import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import ir.mmdkavir.smartattendance.net.Payload
import ir.mmdkavir.smartattendance.net.RepoRef
import ir.mmdkavir.smartattendance.update.Updater
import ir.mmdkavir.smartattendance.util.Fmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {
    private val record = AttendanceRecord(
        eventId = "v1", arrivalMillis = 1762649443000L, zoneId = "Asia/Tehran",
        date = "2026-11-09", time = "01:50:43", workplace = "Mmd",
        lat = 31.9000284, lng = 54.35826, accuracy = 10f, status = "ARRIVED"
    )

    @Test
    fun jalaliConversion() {
        assertEquals(Triple(1405, 8, 18), Fmt.gregorianToJalali(2026, 11, 9))
        assertEquals(Triple(1404, 1, 1), Fmt.gregorianToJalali(2025, 3, 21))
        assertEquals(Triple(1403, 1, 1), Fmt.gregorianToJalali(2024, 3, 20))
    }

    @Test
    fun persianPayloadKeepsNumbers() {
        val s = AppSettings(name = "MmdKavir", employeeId = "85", workplace = "Mmd", msgLang = "fa")
        val p = Payload.arrival(s, record)
        assertEquals("fa", p["lang"])
        assertEquals("85", p["employee_id"])
        assertEquals("01:50:43", p["time"])
        assertEquals("31.9000284", p["lat"])
        assertEquals("54.35826", p["lng"])
    }

    @Test
    fun jalaliDateInPayload() {
        val s = AppSettings(name = "a", workplace = "b", dateFormat = "jalali")
        assertEquals("1405-08-18", Payload.arrival(s, record)["date"])
        assertEquals("2026-11-09", Payload.arrival(AppSettings(name = "a"), record)["date"])
    }

    @Test
    fun coordinatesNeverUseScientificNotation() {
        assertEquals("0.00001", Payload.coord(0.00001))
        assertEquals("31.9000284", Payload.coord(31.9000284))
        assertEquals("-12.5", Payload.coord(-12.5))
    }

    @Test
    fun repositoryUrlParsing() {
        val want = RepoRef("kavirkobra69", "myworkgps")
        assertEquals(want, RepoRef.parse("https://github.com/kavirkobra69/myworkgps"))
        assertEquals(want, RepoRef.parse("https://github.com/kavirkobra69/myworkgps.git"))
        assertEquals(want, RepoRef.parse("github.com/kavirkobra69/myworkgps/tree/main"))
        assertEquals(want, RepoRef.parse("git@github.com:kavirkobra69/myworkgps.git"))
        assertEquals(want, RepoRef.parse(" kavirkobra69/myworkgps "))
        assertNull(RepoRef.parse("https://github.com/onlyowner"))
        assertNull(RepoRef.parse("https://example.com/a/b/../.."))
        assertNull(RepoRef.parse("owner/bad name"))
        assertNull(RepoRef.parse(""))
    }

    @Test
    fun versionComparison() {
        assertTrue(Updater.isNewer("1.1.0", "1.0.9"))
        assertTrue(Updater.isNewer("v1.10.0", "1.9.0"))
        assertFalse(Updater.isNewer("1.1.0", "1.1.0"))
        assertFalse(Updater.isNewer("1.0.0", "1.1.0"))
    }

    @Test
    fun releasePicking() {
        val json = """[
          {"tag_name":"v1.1.0","name":"n","body":"notes","prerelease":false,"published_at":"x","assets":[{"name":"SmartAttendance-v1.1.0.apk","browser_download_url":"https://github.com/kavirkobra69/myworkgps/releases/download/v1.1.0/SmartAttendance-v1.1.0.apk","size":100}]},
          {"tag_name":"v1.0.0","name":"o","body":"","prerelease":false,"published_at":"x","assets":[{"name":"SmartAttendance-v1.0.0.apk","browser_download_url":"https://github.com/kavirkobra69/myworkgps/releases/download/v1.0.0/a.apk","size":50}]}
        ]"""
        val info = Updater.pickLatest(json)
        assertEquals("1.1.0", info?.version)
        assertEquals("notes", info?.notes)
        assertNull(Updater.pickLatest("[]"))
    }
}
