package ir.mmdkavir.smartattendance.net

import ir.mmdkavir.smartattendance.i18n.t
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.delay
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class RepoRef(val owner: String, val name: String) {
    val full get() = "$owner/$name"

    companion object {
        private val SLUG = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")
        private val NAME = Regex("^[A-Za-z0-9._-]{1,100}$")

        /** Accepts https://github.com/o/r, .git, /tree/..., git@github.com:o/r.git and o/r. */
        fun parse(input: String): RepoRef? {
            var text = input.trim()
            text = text.removePrefix("git@github.com:")
            text = text.replace(Regex("^(https?://)?(www\\.)?github\\.com/", RegexOption.IGNORE_CASE), "")
            val parts = text.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val owner = parts[0]
            val name = parts[1].removeSuffix(".git")
            if (!SLUG.matches(owner) || !NAME.matches(name) || name == "." || name == "..") return null
            return RepoRef(owner, name)
        }
    }
}

data class DiagStep(val title: String, val ok: Boolean, val detail: String)

data class GhConnection(
    val login: String,
    val repo: RepoRef,
    val branch: String,
    val secretsPresent: Boolean,
    val steps: List<DiagStep>
)

data class RunInfo(val id: Long, val status: String, val conclusion: String?, val url: String)

class GhException(
    message: String,
    val code: Int = 0,
    val retryable: Boolean = false
) : Exception(message)

object GitHub {
    const val WORKFLOW_FILE = "attendance.yml"
    const val WORKFLOW_PATH = ".github/workflows/attendance.yml"
    const val SECRET_BOT = "TELEGRAM_BOT_TOKEN"
    const val SECRET_CHAT = "TELEGRAM_CHAT_ID"
    private const val API = "https://api.github.com"
    private val JSON = "application/json; charset=utf-8".toMediaType()

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

    /** System DNS first; DNS-over-HTTPS only when the system resolver fails. */
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
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private class Reply(val code: Int, val json: JSONObject?, val scopes: String?, val array: org.json.JSONArray? = null)

    fun describeNetworkError(e: IOException): String = when (e) {
        is UnknownHostException -> t("Cannot resolve api.github.com. Check your internet connection or DNS.")
        is SocketTimeoutException -> t("Connection to GitHub timed out.")
        is SSLException -> t("Secure connection to GitHub failed. Check the phone's date and time.")
        else -> t("Cannot reach GitHub (%s). Check your internet connection.", e.javaClass.simpleName)
    }

    private fun call(token: String, method: String, path: String, body: JSONObject? = null): Reply {
        val builder = Request.Builder()
            .url(API + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "SmartAttendance-Android")
        val rb = body?.toString()?.toRequestBody(JSON)
        builder.method(method, rb ?: if (method == "GET") null else "".toRequestBody(JSON))
        try {
            http.newCall(builder.build()).execute().use { r ->
                val text = r.body?.string().orEmpty()
                val json = try {
                    if (text.startsWith("{")) JSONObject(text) else null
                } catch (e: Exception) {
                    null
                }
                if (r.code >= 400) throw mapError(r.code, json, r.header("x-ratelimit-remaining"), r.header("retry-after"), r.header("x-ratelimit-reset"))
                val array = try {
                    if (text.startsWith("[")) org.json.JSONArray(text) else null
                } catch (e: Exception) {
                    null
                }
                return Reply(r.code, json, r.header("x-oauth-scopes"), array)
            }
        } catch (e: IOException) {
            throw GhException(describeNetworkError(e), retryable = true)
        }
    }

    private fun mapError(code: Int, json: JSONObject?, remaining: String?, retryAfter: String?, reset: String?): GhException {
        val msg = json?.optString("message").orEmpty()
        return when {
            code == 401 -> GhException(t("GitHub rejected the token (invalid, expired or revoked). Create a new token and connect again."), code)
            code == 403 && (remaining == "0" || msg.contains("rate limit", true) || retryAfter != null) -> {
                val wait = retryAfter?.toLongOrNull()
                    ?: reset?.toLongOrNull()?.let { (it - System.currentTimeMillis() / 1000).coerceAtLeast(1) }
                GhException(wait?.let { t("GitHub rate limit reached, retry in about %ss", it) } ?: t("GitHub rate limit reached"), code, retryable = true)
            }
            code == 403 -> GhException((t("The token does not have the required permission.") + " " + msg).trim(), code)
            code == 404 -> GhException(t("Not found. Check the repository URL, and that the token can access this repository."), code)
            code == 422 -> GhException(t("GitHub refused the request: %s", msg.ifBlank { t("invalid data") }), code)
            code >= 500 || code == 429 -> GhException(t("GitHub is temporarily unavailable (HTTP %s).", code), code, retryable = true)
            else -> GhException(t("GitHub error HTTP %s: %s", code, msg), code)
        }
    }

    private fun repoPath(repo: RepoRef) = "/repos/${repo.owner}/${repo.name}"

    private fun loadWorkflow(context: Context): String =
        context.assets.open("attendance.yml").use { it.readBytes().toString(Charsets.UTF_8) }

    /** Validates the token and repository, installs/updates the workflow and checks the secrets. */
    fun connect(context: Context, token: String, repoUrl: String): GhConnection {
        val steps = mutableListOf<DiagStep>()
        val repo = RepoRef.parse(repoUrl)
            ?: throw GhException(t("Repository URL is not valid. Use https://github.com/owner/repository"))
        val tok = token.trim()
        if (tok.length < 20 || tok.any { it.isWhitespace() }) throw GhException(t("Token looks invalid. Paste the full Personal Access Token."))

        fun fail(title: String, e: GhException): Nothing {
            steps += DiagStep(title, false, e.message ?: t("Failed"))
            throw StepsException(steps, e)
        }

        val user = try {
            call(tok, "GET", "/user")
        } catch (e: GhException) {
            fail(t("GitHub token"), e)
        }
        val login = user.json?.optString("login").orEmpty()
        steps += DiagStep(t("GitHub token"), true, t("Signed in as %s", login))

        val repoReply = try {
            call(tok, "GET", repoPath(repo))
        } catch (e: GhException) {
            fail(t("Repository"), e)
        }
        val info = repoReply.json ?: throw GhException(t("Unexpected answer from GitHub"))
        val branch = info.optString("default_branch", "main")
        val perms = info.optJSONObject("permissions")
        val canPush = perms?.optBoolean("push", false) ?: false
        val fullName = info.optString("full_name", repo.full)
        val actual = RepoRef.parse(fullName) ?: repo
        if (!canPush) {
            fail(t("Repository"), GhException(t("The token can only read %s. It needs write access (Contents, Actions, Workflows and Secrets).", fullName)))
        }
        steps += DiagStep(t("Repository"), true, t("%s, default branch \"%s\", %s", fullName, branch, if (info.optBoolean(t("private"))) t("private") else t("public")))

        // Workflow file
        val existing = try {
            call(tok, "GET", "${repoPath(actual)}/contents/$WORKFLOW_PATH?ref=$branch")
        } catch (e: GhException) {
            if (e.code == 404) null else fail(t("Workflow file"), e)
        }
        val desired = loadWorkflow(context)
        val currentText = existing?.json?.optString("content")?.let {
            try {
                String(Base64.decode(it.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: Exception) {
                ""
            }
        }
        if (existing == null || currentText?.trim() != desired.trim()) {
            val put = JSONObject()
                .put("message", if (existing == null) t("Add Smart Attendance workflow") else t("Update Smart Attendance workflow"))
                .put("content", Base64.encodeToString(desired.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                .put("branch", branch)
            existing?.json?.optString("sha")?.takeIf { it.isNotBlank() }?.let { put.put("sha", it) }
            try {
                call(tok, "PUT", "${repoPath(actual)}/contents/$WORKFLOW_PATH", put)
            } catch (e: GhException) {
                val hint = if (e.code == 403 || e.code == 404) {
                    t(" Writing workflow files needs the \"Contents\" and \"Workflows\" write permissions (classic token: repo + workflow scopes). If the branch is protected, allow this token to push.")
                } else ""
                fail(t("Workflow file"), GhException((e.message ?: t("Failed")) + hint, e.code, e.retryable))
            }
            steps += DiagStep(t("Workflow file"), true, if (existing == null) t("Created %s", WORKFLOW_PATH) else t("Updated %s", WORKFLOW_PATH))
        } else {
            steps += DiagStep(t("Workflow file"), true, t("%s is up to date", WORKFLOW_PATH))
        }

        // Workflow must be registered and active
        var wf: JSONObject? = null
        var lastError: GhException? = null
        for (attempt in 1..5) {
            try {
                wf = call(tok, "GET", "${repoPath(actual)}/actions/workflows/$WORKFLOW_FILE").json
                break
            } catch (e: GhException) {
                lastError = e
                if (e.code != 404) break
                Thread.sleep(2000)
            }
        }
        if (wf == null) {
            fail(t("GitHub Actions"), GhException(
                t("The workflow is not visible to GitHub Actions yet%s. Make sure Actions are enabled for this repository (Settings → Actions) and try again.", lastError?.message?.let { ": $it" } ?: ""),
                lastError?.code ?: 0
            ))
        }
        if (wf.optString("state") != "active") {
            try {
                call(tok, "PUT", "${repoPath(actual)}/actions/workflows/${wf.optLong("id")}/enable")
            } catch (e: GhException) {
                fail(t("GitHub Actions"), GhException(t("The workflow is disabled and could not be enabled: %s", e.message), e.code))
            }
        }
        steps += DiagStep(t("GitHub Actions"), true, t("Workflow \"%s\" is active", wf.optString("name")))

        // Secrets
        var present = false
        try {
            val bot = secretExists(tok, actual, SECRET_BOT)
            val chat = secretExists(tok, actual, SECRET_CHAT)
            present = bot && chat
            steps += DiagStep(
                t("Telegram secrets"), present,
                if (present) t("TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID are set")
                else t("Missing: %s. Enter the bot token and Chat ID below and press Save Telegram settings.", listOfNotNull(if (!bot) SECRET_BOT else null, if (!chat) SECRET_CHAT else null).joinToString(", "))
            )
        } catch (e: GhException) {
            steps += DiagStep(t("Telegram secrets"), false, t("Could not check secrets: %s (needs the \"Secrets\" permission)", e.message))
        }
        return GhConnection(login, actual, branch, present, steps)
    }

    class StepsException(val steps: List<DiagStep>, cause: GhException) : Exception(cause.message, cause) {
        val gh: GhException get() = cause as GhException
    }

    private fun secretExists(token: String, repo: RepoRef, name: String): Boolean = try {
        call(token, "GET", "${repoPath(repo)}/actions/secrets/$name")
        true
    } catch (e: GhException) {
        if (e.code == 404) false else throw e
    }

    /** Stores the Telegram credentials as encrypted Actions secrets. They are never kept on the phone. */
    fun storeSecrets(token: String, repo: RepoRef, botToken: String, chatId: String) {
        val keyJson = try {
            call(token, "GET", "${repoPath(repo)}/actions/secrets/public-key").json
        } catch (e: GhException) {
            throw GhException(t("Could not read the repository secrets key: %s The token needs the \"Secrets\" read and write permission.", e.message), e.code, e.retryable)
        } ?: throw GhException(t("Unexpected answer from GitHub"))
        val keyId = keyJson.optString("key_id")
        val key = keyJson.optString("key")
        for ((name, value) in listOf(SECRET_BOT to botToken, SECRET_CHAT to chatId)) {
            val body = JSONObject().put("encrypted_value", Sealer.seal(key, value)).put("key_id", keyId)
            try {
                call(token, "PUT", "${repoPath(repo)}/actions/secrets/$name", body)
            } catch (e: GhException) {
                throw GhException(t("Could not save secret %s: %s", name, e.message), e.code, e.retryable)
            }
        }
    }

    fun dispatch(token: String, repo: RepoRef, branch: String, inputs: Map<String, String>) {
        val body = JSONObject().put("ref", branch).put("inputs", JSONObject(inputs as Map<*, *>))
        try {
            call(token, "POST", "${repoPath(repo)}/actions/workflows/$WORKFLOW_FILE/dispatches", body)
        } catch (e: GhException) {
            val hint = when (e.code) {
                404 -> t(" The workflow or repository was not found. Open Settings and press Connect again.")
                403 -> t(" The token needs the \"Actions\" write permission.")
                422 -> t(" Press Connect to reinstall the workflow.")
                else -> ""
            }
            throw GhException((e.message ?: t("Failed")) + hint, e.code, e.retryable)
        }
    }

    /** Finds the run started for [dispatchId] (the workflow's run-name is "Attendance <event_id>"). */
    fun findRun(token: String, repo: RepoRef, dispatchId: String): RunInfo? {
        val json = call(
            token, "GET",
            "${repoPath(repo)}/actions/workflows/$WORKFLOW_FILE/runs?event=workflow_dispatch&per_page=30"
        ).json ?: return null
        val runs = json.optJSONArray("workflow_runs") ?: return null
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            if (r.optString("display_title") == "Attendance $dispatchId") {
                return RunInfo(
                    r.optLong("id"), r.optString("status"),
                    r.optString("conclusion").takeIf { it.isNotBlank() && it != "null" },
                    r.optString("html_url")
                )
            }
        }
        return null
    }

    /** Best-effort reason for a failed run: the workflow's own error annotation, else the failed step. */
    fun failureReason(token: String, repo: RepoRef, runId: Long): String? = try {
        val jobs = call(token, "GET", "${repoPath(repo)}/actions/runs/$runId/jobs").json?.optJSONArray("jobs")
        var reason: String? = null
        if (jobs != null) {
            for (i in 0 until jobs.length()) {
                val job = jobs.getJSONObject(i)
                if (job.optString("conclusion") != "failure") continue
                val notes = call(token, "GET", "${repoPath(repo)}/check-runs/${job.optLong("id")}/annotations").array
                val message = notes?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("annotation_level") == "failure" && !it.optString("message").startsWith("Process completed") }
                        ?.optString("message")
                }
                if (!message.isNullOrBlank()) {
                    reason = message
                    break
                }
                reason = job.optJSONArray("steps")?.let { steps ->
                    (0 until steps.length()).map { steps.getJSONObject(it) }
                        .firstOrNull { it.optString("conclusion") == "failure" }?.optString("name")
                }?.let { t("step \"%s\" failed", it) }
                break
            }
        }
        reason
    } catch (e: Exception) {
        null
    }

    /** Sends a test message through the full path and waits for the run to finish. */
    suspend fun sendTest(
        token: String, repo: RepoRef, branch: String, inputs: Map<String, String>,
        progress: (String) -> Unit
    ): List<DiagStep> {
        val id = "test-" + System.currentTimeMillis()
        val steps = mutableListOf<DiagStep>()
        try {
            progress(t("Triggering GitHub Actions…"))
            dispatch(token, repo, branch, inputs + ("event_id" to id))
            steps += DiagStep(t("Trigger workflow"), true, t("Accepted by GitHub"))
            val deadline = System.currentTimeMillis() + 150_000
            var run: RunInfo? = null
            while (System.currentTimeMillis() < deadline) {
                delay(5_000)
                run = findRun(token, repo, id)
                progress(if (run == null) t("Waiting for the run to start…") else t("Run %s…", run.status))
                if (run?.status == "completed") break
            }
            if (run == null) {
                steps += DiagStep(t("Workflow run"), false, t("No run appeared within 2.5 minutes. Check the Actions tab of the repository."))
            } else if (run.status != "completed") {
                steps += DiagStep(t("Workflow run"), false, t("Still running after 2.5 minutes: %s", run.url))
            } else if (run.conclusion == "success") {
                steps += DiagStep(t("Workflow run"), true, t("Finished successfully"))
                steps += DiagStep(t("Telegram"), true, t("Message sent. Check your Telegram chat."))
            } else {
                val why = failureReason(token, repo, run.id)
                steps += DiagStep(
                    t("Workflow run"), false,
                    t("Run %s%s. Open %s to read the error. Most often the Telegram bot token or Chat ID is wrong, or you have not pressed Start in your bot.", run.conclusion, why?.let { " ($it)" } ?: "", run.url)
                )
            }
        } catch (e: GhException) {
            steps += DiagStep(t("Trigger workflow"), false, e.message ?: t("Failed"))
        }
        return steps
    }
}
