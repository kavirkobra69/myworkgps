package ir.mmdkavir.smartattendance.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import java.io.File

object Export {
    private fun esc(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun csv(rows: List<AttendanceRecord>): String = buildString {
        append("date,arrival_time,timezone,workplace,status,latitude,longitude,accuracy_m,left_time,telegram_sent\n")
        rows.forEach { r ->
            val left = r.leftMillis?.let { Fmt.time(it) } ?: ""
            append(
                listOf(
                    r.date, r.time, r.zoneId, esc(r.workplace), r.status,
                    r.lat.toString(), r.lng.toString(), r.accuracy.toString(),
                    left, r.sent.toString()
                ).joinToString(",")
            ).append('\n')
        }
    }

    fun share(context: Context, rows: List<AttendanceRecord>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "attendance.csv")
        file.writeText(csv(rows))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export attendance").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
