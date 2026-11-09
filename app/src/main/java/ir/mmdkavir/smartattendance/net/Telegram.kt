package ir.mmdkavir.smartattendance.net

import ir.mmdkavir.smartattendance.data.AppSettings
import ir.mmdkavir.smartattendance.data.AttendanceRecord
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class ChatInfo(val id: String, val type: String, val name: String)

data class DiagStep(val title: String, val ok: Boolean, val detail: String)

sealed class TgResult {
    data class Ok(val info: String = "") : TgResult()
    data class Fail(val message: String, val retryable: Boolean) : TgResult()
}

object Messages {
    private fun link(r: AttendanceRecord) =
        "https://www.openstreetmap.org/?mlat=${r.lat}&mlon=${r.lng}#map=18/${r.lat}/${r.lng}"

    fun arrival(s: AppSettings, r: AttendanceRecord): String {
        val zone = try {
            java.time.ZoneId.of(r.zoneId)
        } catch (e: Exception) {
            java.time.ZoneId.systemDefault()
        }
        val date = ir.mmdkavir.smartattendance.util.Fmt.dateStyled(r.arrivalMillis, s.dateFormat, zone)
        return if (s.msgLang == "fa") {
            buildString {
                append("حضور در محل کار\n\n")
                append("نام: ${s.name}\n")
                if (s.employeeId.isNotBlank()) append("شناسه کارمندی: ${s.employeeId}\n")
                append("محل کار: ${r.workplace}\n")
                append("تاریخ: $date\n")
                append("ساعت ورود: ${r.time}\n")
                append("موقعیت: ${link(r)}\n\n")
                append("وضعیت: وارد شد")
            }
        } else {
            buildString {
                append("WORK ATTENDANCE\n\n")
                append("Name: ${s.name}\n")
                if (s.employeeId.isNotBlank()) append("Employee ID: ${s.employeeId}\n")
                append("Workplace: ${r.workplace}\n")
                append("Date: $date\n")
                append("Arrival Time: ${r.time}\n")
                append("Location: ${link(r)}\n\n")
                append("Status: ARRIVED")
            }
        }
    }

    fun test(s: AppSettings): String = if (s.msgLang == "fa") {
        buildString {
            append("پیام آزمایشی Smart Attendance\n\n")
            if (s.name.isNotBlank()) append("نام: ${s.name}\n")
            append("اتصال از طریق Worker برقرار است.")
        }
    } else {
        buildString {
            append("Smart Attendance test message\n\n")
            if (s.name.isNotBlank()) append("Name: ${s.name}\n")
            append("Connection through the Worker is working.")
        }
    }
}

object TelegramClient {
    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val doh: DnsOverHttps? = try {
        DnsOverHttps.Builder()
            .client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrlOrNull()!!)
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .build()
    } catch (e: Exception) {
        null
    }

    internal val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            doh?.lookup(hostname) ?: throw e
        }
    }

    private val http = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private class Raw(val code: Int, val json: JSONObject?, val netError: String?)

    fun base(url: String): String? {
        val u = url.trim().trimEnd('/')
        return if (u.startsWith("https://") && u.toHttpUrlOrNull() != null) u else null
    }

    fun describeNetworkError(e: IOException, host: String): String = when (e) {
        is UnknownHostException -> "Cannot resolve $host. The domain may be blocked or DNS is filtered on this network."
        is SocketTimeoutException -> "Timed out reaching $host. This network may be dropping the connection."
        is SSLException -> "Secure connection to $host failed. The network may be interfering with HTTPS."
        is ConnectException -> "Cannot connect to $host. It may be blocked, or you are offline."
        else -> "Network error reaching $host (${e.javaClass.simpleName})."
    }

    private fun raw(req: Request): Raw = try {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                null
            }
            Raw(resp.code, json, null)
        }
    } catch (e: IOException) {
        Raw(-1, null, describeNetworkError(e, req.url.host))
    }

    private fun isOk(r: Raw) = r.netError == null && r.code in 200..299 && r.json?.optBoolean("ok", false) == true

    private fun failure(r: Raw): TgResult.Fail {
        if (r.netError != null) return TgResult.Fail(r.netError, true)
        val msg = r.json?.optString("error")?.takeIf { it.isNotBlank() } ?: when (r.code) {
            404 -> "HTTP 404: this address is not your Worker"
            401 -> "Unauthorized"
            else -> "HTTP ${r.code}"
        }
        return TgResult.Fail(msg, r.code >= 500 || r.code == 429 || r.code == 408)
    }

    private fun get(url: String, appKey: String?): Raw {
        val b = Request.Builder().url(url).get()
        if (appKey != null) b.header("X-App-Key", appKey)
        return raw(b.build())
    }

    fun health(workerUrl: String, appKey: String): TgResult {
        val b = base(workerUrl) ?: return TgResult.Fail("Worker URL must be a valid https:// address", false)
        val r = get("$b/health", appKey)
        if (!isOk(r)) return failure(r)
        val bot = r.json?.optString("bot").orEmpty()
        return TgResult.Ok(if (bot.isNotBlank()) "@$bot" else "")
    }

    fun send(workerUrl: String, appKey: String, chatId: String, text: String, eventId: String): TgResult {
        val b = base(workerUrl) ?: return TgResult.Fail("Worker URL must be a valid https:// address", false)
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
            .put("event_id", eventId)
            .toString()
        val r = raw(
            Request.Builder()
                .url("$b/send")
                .header("X-App-Key", appKey)
                .post(payload.toRequestBody(JSON))
                .build()
        )
        return if (isOk(r)) TgResult.Ok() else failure(r)
    }

    fun detectChats(workerUrl: String, appKey: String): Pair<List<ChatInfo>, String?> {
        val b = base(workerUrl) ?: return emptyList<ChatInfo>() to "Worker URL must be a valid https:// address"
        val r = get("$b/detect", appKey)
        if (!isOk(r)) return emptyList<ChatInfo>() to failure(r).message
        val arr = r.json?.optJSONArray("chats")
        val out = mutableListOf<ChatInfo>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += ChatInfo(o.optString("id"), o.optString("type"), o.optString("name"))
            }
        }
        return out to null
    }

    fun diagnose(workerUrl: String, appKey: String, chatId: String): List<DiagStep> {
        val steps = mutableListOf<DiagStep>()
        val b = base(workerUrl)
        if (b == null) {
            steps += DiagStep("Worker address", false, "Enter a valid https:// Worker URL")
            return steps
        }
        val host = b.toHttpUrlOrNull()?.host ?: b

        val reach = get("$b/", null)
        if (reach.netError != null) {
            steps += DiagStep("Reach the Worker ($host)", false, reach.netError)
            return steps
        }
        if (reach.json?.optString("service") != "smart-attendance-relay") {
            steps += DiagStep("Reach the Worker ($host)", false, "The address answered (HTTP ${reach.code}) but it is not your Smart Attendance Worker")
            return steps
        }
        steps += DiagStep("Reach the Worker ($host)", true, "Worker is online")

        val url = b.toHttpUrlOrNull()!!.newBuilder().addPathSegment("health").apply {
            if (chatId.isNotBlank()) addQueryParameter("chat_id", chatId.trim())
        }.build().toString()
        val health = get(url, appKey)
        if (health.netError != null) {
            steps += DiagStep("Access key", false, health.netError)
            return steps
        }
        if (health.code == 401) {
            steps += DiagStep("Access key", false, "The Worker rejected the key. It must equal the Worker's APP_KEY secret")
            return steps
        }
        if (health.code == 429) {
            steps += DiagStep("Access key", false, "Too many requests. Wait a minute and try again")
            return steps
        }
        if (!isOk(health)) {
            val msg = failure(health).message
            if (health.code == 500 && msg.startsWith("Worker is not")) {
                steps += DiagStep("Worker secrets", false, "$msg. Redeploy the Worker so BOT_TOKEN and APP_KEY are set")
            } else {
                steps += DiagStep("Access key", true, "Accepted")
                steps += DiagStep("Telegram bot", false, msg)
            }
            return steps
        }
        steps += DiagStep("Access key", true, "Accepted")
        val bot = health.json?.optString("bot").orEmpty()
        steps += DiagStep("Telegram bot", true, if (bot.isNotBlank()) "@$bot is valid" else "Bot is valid")

        val chat = health.json?.optJSONObject("chat")
        if (chat != null) {
            if (chat.optBoolean("ok", false)) {
                steps += DiagStep("Chat ID", true, chat.optString("name").ifBlank { "Chat is reachable" })
            } else {
                steps += DiagStep("Chat ID", false, chat.optString("error").ifBlank { "Chat not reachable" } + ". Open your bot in Telegram and press Start first")
            }
        }
        return steps
    }
}
