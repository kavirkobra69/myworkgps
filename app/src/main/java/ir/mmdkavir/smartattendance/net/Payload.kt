package ir.mmdkavir.smartattendance.net

import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import ir.mmdkavir.smartattendance.util.Fmt
import org.json.JSONObject
import java.util.Locale

/** Inputs sent to the GitHub Actions "Attendance" workflow. The workflow builds the Telegram text. */
object Payload {
    /** Date shown in the message: the record's own date, converted to Jalali when selected. */
    fun recordDate(r: AttendanceRecord, format: String): String {
        if (format != "jalali") return r.date
        val p = r.date.split("-").mapNotNull { it.toIntOrNull() }
        if (p.size != 3) return r.date
        val (y, m, d) = Fmt.gregorianToJalali(p[0], p[1], p[2])
        return "%04d-%02d-%02d".format(y, m, d)
    }

    fun coord(value: Double): String = String.format(Locale.US, "%.7f", value).trimEnd('0').trimEnd('.')

    fun arrival(s: AppSettings, r: AttendanceRecord): Map<String, String> = linkedMapOf(
        "kind" to "arrival",
        "lang" to if (s.msgLang == "fa") "fa" else "en",
        "name" to s.name,
        "employee_id" to s.employeeId,
        "workplace" to r.workplace,
        "date" to recordDate(r, s.dateFormat),
        "time" to r.time,
        "lat" to coord(r.lat),
        "lng" to coord(r.lng)
    )

    fun test(s: AppSettings): Map<String, String> = linkedMapOf(
        "kind" to "test",
        "lang" to if (s.msgLang == "fa") "fa" else "en",
        "name" to s.name
    )

    fun toJson(inputs: Map<String, String>): String = JSONObject(inputs as Map<*, *>).toString()

    fun fromJson(json: String): Map<String, String> {
        val o = JSONObject(json)
        val out = linkedMapOf<String, String>()
        for (k in o.keys()) out[k] = o.optString(k)
        return out
    }
}
