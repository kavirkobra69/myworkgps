package ir.mmdkavir.smartattendance.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Fmt {
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun date(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DATE.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun time(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun dateTime(ms: Long): String = date(ms) + " " + time(ms)

    fun dateStyled(ms: Long, format: String, zone: ZoneId = ZoneId.systemDefault()): String {
        if (format != "jalali") return date(ms, zone)
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val (jy, jm, jd) = gregorianToJalali(d.year, d.monthValue, d.dayOfMonth)
        return "%04d-%02d-%02d".format(java.util.Locale.US, jy, jm, jd)
    }

    fun dateTimeStyled(ms: Long, format: String): String = dateStyled(ms, format) + " " + time(ms)

    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val offsets = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var jy: Int
        val g: Int
        if (gy > 1600) {
            jy = 979
            g = gy - 1600
        } else {
            jy = 0
            g = gy - 621
        }
        val g2 = if (gm > 2) g + 1 else g
        var days = 365 * g + (g2 + 3) / 4 - (g2 + 99) / 100 + (g2 + 399) / 400 - 80 + gd + offsets[gm - 1]
        jy += 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + days / 31
            jd = 1 + days % 31
        } else {
            jm = 7 + (days - 186) / 30
            jd = 1 + (days - 186) % 30
        }
        return Triple(jy, jm, jd)
    }
}
