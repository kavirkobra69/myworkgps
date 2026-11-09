package ir.mmdkavir.smartattendance

import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import ir.mmdkavir.smartattendance.net.CloudflareDeployer
import ir.mmdkavir.smartattendance.net.Messages
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
    fun englishMessageFormat() {
        val s = AppSettings(name = "MmdKavir", employeeId = "85", workplace = "Mmd")
        val t = Messages.arrival(s, record)
        assertTrue(t.startsWith("WORK ATTENDANCE\n\nName: MmdKavir\nEmployee ID: 85\nWorkplace: Mmd\nDate: "))
        assertTrue(t.contains("Arrival Time: 01:50:43"))
        assertTrue(t.contains("Location: https://www.openstreetmap.org/?mlat=31.9000284&mlon=54.35826#map=18/31.9000284/54.35826"))
        assertTrue(t.endsWith("\n\nStatus: ARRIVED"))
    }

    @Test
    fun persianMessageKeepsNumbers() {
        val s = AppSettings(name = "MmdKavir", employeeId = "85", workplace = "Mmd", msgLang = "fa")
        val t = Messages.arrival(s, record)
        assertFalse(t.contains("WORK ATTENDANCE"))
        assertTrue(t.contains("85"))
        assertTrue(t.contains("01:50:43"))
        assertTrue(t.contains("mlat=31.9000284"))
    }

    @Test
    fun jalaliDateInMessage() {
        val s = AppSettings(name = "a", workplace = "b", dateFormat = "jalali")
        assertTrue(Messages.arrival(s, record).contains("1405"))
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

    @Test
    fun deployerValidation() {
        assertTrue(CloudflareDeployer.validAccountId("0123456789abcdef0123456789abcdef"))
        assertFalse(CloudflareDeployer.validAccountId("short"))
        assertTrue(CloudflareDeployer.validBotToken("123456:ABCdefGhIJKlmNoPQRsTUVwxyZ_12345"))
        assertFalse(CloudflareDeployer.validBotToken("nope"))
    }
}
