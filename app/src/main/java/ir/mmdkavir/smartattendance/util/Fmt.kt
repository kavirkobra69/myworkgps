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
}
