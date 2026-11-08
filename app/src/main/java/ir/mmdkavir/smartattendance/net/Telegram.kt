package ir.mmdkavir.smartattendance.net

import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class TgResult {
    data class Ok(val info: String = "") : TgResult()
    data class Fail(val message: String, val retryable: Boolean) : TgResult()
}

object Messages {
    fun arrival(s: AppSettings, r: AttendanceRecord): String = buildString {
        append("WORK ATTENDANCE\n\n")
        append("Name: ${s.name}\n")
        if (s.employeeId.isNotBlank()) append("Employee ID: ${s.employeeId}\n")
        append("Workplace: ${r.workplace}\n")
        append("Date: ${r.date}\n")
        append("Arrival Time: ${r.time}\n")
        append("Timezone: ${r.zoneId}\n")
        append("Location: https://www.openstreetmap.org/?mlat=${r.lat}&mlon=${r.lng}#map=18/${r.lat}/${r.lng}\n\n")
        append("Status: ARRIVED")
    }

    fun test(s: AppSettings): String = buildString {
        append("Smart Attendance test message\n\n")
        if (s.name.isNotBlank()) append("Name: ${s.name}\n")
        append("Connection through the Worker is working.")
    }
}

object TelegramClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun base(url: String): String? {
        val u = url.trim().trimEnd('/')
        return if (u.startsWith("https://") && u.length > 10) u else null
    }

    fun health(workerUrl: String, appKey: String): TgResult {
        val b = base(workerUrl) ?: return TgResult.Fail("Worker URL must start with https://", false)
        return call({
            Request.Builder().url("$b/health").header("X-App-Key", appKey).get().build()
        }) { json ->
            val bot = json.optString("bot")
            TgResult.Ok(if (bot.isNotBlank()) "@$bot" else "")
        }
    }

    fun send(workerUrl: String, appKey: String, chatId: String, text: String, eventId: String): TgResult {
        val b = base(workerUrl) ?: return TgResult.Fail("Worker URL must start with https://", false)
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("event_id", eventId)
            .toString()
        return call({
            Request.Builder()
                .url("$b/send")
                .header("X-App-Key", appKey)
                .post(payload.toRequestBody(JSON))
                .build()
        }) { TgResult.Ok() }
    }

    private fun call(build: () -> Request, onOk: (JSONObject) -> TgResult): TgResult = try {
        http.newCall(build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(raw)
            } catch (e: Exception) {
                JSONObject()
            }
            if (resp.isSuccessful && json.optBoolean("ok", false)) {
                onOk(json)
            } else {
                val msg = json.optString("error").ifBlank { "HTTP ${resp.code}" }
                TgResult.Fail(msg, resp.code >= 500 || resp.code == 429 || resp.code == 408)
            }
        }
    } catch (e: IOException) {
        TgResult.Fail("Network error (${e.javaClass.simpleName})", true)
    } catch (e: IllegalArgumentException) {
        TgResult.Fail("Invalid Worker URL", false)
    }
}
