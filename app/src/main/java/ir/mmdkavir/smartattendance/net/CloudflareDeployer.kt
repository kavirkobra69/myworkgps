package ir.mmdkavir.smartattendance.net

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class DeployException(message: String, val code: Int = 0) : Exception(message)

object CloudflareDeployer {
    const val SCRIPT_NAME = "smart-attendance-relay"
    private const val API = "https://api.cloudflare.com/client/v4"
    private const val COMPATIBILITY_DATE = "2024-11-01"
    private val JSON = "application/json".toMediaType()

    private val http = OkHttpClient.Builder()
        .dns(TelegramClient.dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun generateKey(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun validAccountId(id: String) = Regex("^[0-9a-fA-F]{32}$").matches(id.trim())

    fun validBotToken(token: String) = Regex("^\\d{5,}:[A-Za-z0-9_-]{20,}$").matches(token.trim())

    private fun errorText(json: JSONObject?, http: Int): String {
        val errors = json?.optJSONArray("errors")
        val parts = mutableListOf<String>()
        if (errors != null) {
            for (i in 0 until errors.length()) {
                val e = errors.getJSONObject(i)
                parts += "${e.optString("message")} (${e.optInt("code")})"
            }
        }
        val base = if (parts.isEmpty()) "Cloudflare answered HTTP $http" else parts.joinToString("; ")
        return if (http == 401 || http == 403) {
            "$base. Check that the API token is valid and has Workers Scripts: Edit permission for this account."
        } else {
            base
        }
    }

    private fun call(req: Request): JSONObject = try {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                null
            }
            if (json == null) throw DeployException("Cloudflare answered HTTP ${resp.code} without JSON", resp.code)
            if (!json.optBoolean("success", false)) {
                val first = json.optJSONArray("errors")?.optJSONObject(0)?.optInt("code") ?: resp.code
                throw DeployException(errorText(json, resp.code), first)
            }
            json
        }
    } catch (e: IOException) {
        throw DeployException(TelegramClient.describeNetworkError(e, req.url.host))
    }

    private fun request(path: String, token: String) =
        Request.Builder().url("$API$path").header("Authorization", "Bearer $token")

    private fun ensureSubdomain(account: String, token: String): String {
        try {
            val sub = call(request("/accounts/$account/workers/subdomain", token).get().build())
                .optJSONObject("result")?.optString("subdomain").orEmpty()
            if (sub.isNotBlank()) return sub
        } catch (e: DeployException) {
            if (e.code != 10007 && e.code != 404) throw e
        }
        val letters = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        var last: DeployException? = null
        repeat(3) {
            val name = "sa-" + (1..8).map { letters[rnd.nextInt(letters.length)] }.joinToString("")
            try {
                val body = JSONObject().put("subdomain", name).toString().toRequestBody(JSON)
                call(request("/accounts/$account/workers/subdomain", token).put(body).build())
                return name
            } catch (e: DeployException) {
                last = e
            }
        }
        throw last ?: DeployException("Could not create a workers.dev subdomain")
    }

    fun deploy(context: Context, accountId: String, apiToken: String, botToken: String, appKey: String): String {
        val account = accountId.trim()
        val token = apiToken.trim()
        if (!validAccountId(account)) throw DeployException("Account ID must be 32 hexadecimal characters")
        if (token.isBlank()) throw DeployException("Enter the Cloudflare API token")
        if (!validBotToken(botToken)) throw DeployException("The bot token format looks wrong. Copy it again from BotFather")

        val code = context.assets.open("index.js").bufferedReader().use { it.readText() }
        val subdomain = ensureSubdomain(account, token)

        val bindings = JSONArray()
            .put(JSONObject().put("type", "secret_text").put("name", "BOT_TOKEN").put("text", botToken.trim()))
            .put(JSONObject().put("type", "secret_text").put("name", "APP_KEY").put("text", appKey))
        val metadata = JSONObject()
            .put("main_module", "index.js")
            .put("compatibility_date", COMPATIBILITY_DATE)
            .put("bindings", bindings)

        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", null, metadata.toString().toRequestBody(JSON))
            .addFormDataPart("index.js", "index.js", code.toRequestBody("application/javascript+module".toMediaType()))
            .build()
        call(request("/accounts/$account/workers/scripts/$SCRIPT_NAME", token).put(form).build())

        val enable = JSONObject().put("enabled", true).toString().toRequestBody(JSON)
        call(request("/accounts/$account/workers/scripts/$SCRIPT_NAME/subdomain", token).post(enable).build())

        val url = "https://$SCRIPT_NAME.$subdomain.workers.dev"
        if (url.toHttpUrlOrNull() == null) throw DeployException("Cloudflare returned an unexpected subdomain")
        return url
    }
}
