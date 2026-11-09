package ir.mmdkavir.smartattendance.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import ir.mmdkavir.smartattendance.net.GitHub
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val tag: String,
    val version: String,
    val title: String,
    val notes: String,
    val prerelease: Boolean,
    val publishedAt: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
    val sha256Url: String?
)

class UpdateException(message: String) : Exception(message)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpToDate(val current: String) : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState()
    data class Ready(val info: UpdateInfo, val file: File, val note: String) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

enum class InstallResult { STARTED, NEEDS_PERMISSION }

object Updater {
    const val REPO = "kavirkobra69/myworkgps"
    private const val RELEASES_URL = "https://api.github.com/repos/$REPO/releases?per_page=15"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private val APK_NAME = Regex("^[A-Za-z0-9._-]{1,100}\\.apk$")

    private val http = OkHttpClient.Builder()
        .dns(GitHub.dns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    fun versionParts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = versionParts(remote)
        val b = versionParts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun pickLatest(releasesJson: String): UpdateInfo? {
        val arr = JSONArray(releasesJson)
        var best: UpdateInfo? = null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft", false)) continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk: org.json.JSONObject? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name").endsWith(".apk")) {
                    apk = a
                    break
                }
            }
            if (apk == null) continue
            val name = apk.optString("name")
            var shaUrl: String? = null
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.optString("name") == "$name.sha256") shaUrl = a.optString("browser_download_url")
            }
            val digest = apk.optString("digest")
            val sha = if (digest.startsWith("sha256:")) digest.removePrefix("sha256:").lowercase() else null
            val tag = r.optString("tag_name")
            val info = UpdateInfo(
                tag = tag,
                version = tag.removePrefix("v"),
                title = r.optString("name").ifBlank { tag },
                notes = r.optString("body"),
                prerelease = r.optBoolean("prerelease", false),
                publishedAt = r.optString("published_at"),
                apkName = name,
                apkUrl = apk.optString("browser_download_url"),
                apkSize = apk.optLong("size", -1L),
                sha256 = sha,
                sha256Url = shaUrl
            )
            if (best == null || isNewer(info.version, best.version)) best = info
        }
        return best
    }

    fun fetchLatest(context: Context): UpdateInfo {
        val req = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "SmartAttendance/${currentVersion(context)}")
            .build()
        val body: String = try {
            http.newCall(req).execute().use { resp ->
                when (resp.code) {
                    200 -> Unit
                    403, 429 -> throw UpdateException("GitHub rate limit reached. Try again in a few minutes.")
                    404 -> throw UpdateException("No releases found for $REPO.")
                    else -> throw UpdateException("GitHub answered HTTP ${resp.code}.")
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw UpdateException(GitHub.describeNetworkError(e) + " Updates are downloaded from GitHub.")
        }
        return pickLatest(body) ?: throw UpdateException("No release with an APK file was found.")
    }

    private fun fetchSha(url: String): String? = try {
        if (!url.startsWith(DOWNLOAD_PREFIX)) {
            null
        } else {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    null
                } else {
                    resp.body?.string()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                        ?.lowercase()?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        if (!info.apkUrl.startsWith(DOWNLOAD_PREFIX)) throw UpdateException("The release file is not hosted in the official repository.")
        if (!APK_NAME.matches(info.apkName)) throw UpdateException("The release file name is not valid.")

        val dir = File(context.cacheDir, "update")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, info.apkName + ".part")
        val target = File(dir, info.apkName)
        val expectedSha = info.sha256 ?: info.sha256Url?.let { fetchSha(it) }

        try {
            http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw UpdateException("Download failed (HTTP ${resp.code}).")
                val body = resp.body ?: throw UpdateException("Download failed (empty response).")
                val total = if (body.contentLength() > 0) body.contentLength() else info.apkSize
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val p = (read * 100 / total).toInt().coerceIn(0, 100)
                                if (p != lastPercent) {
                                    lastPercent = p
                                    onProgress(p)
                                }
                            }
                        }
                    }
                }
                if (info.apkSize > 0 && read != info.apkSize) {
                    throw UpdateException("The download is incomplete ($read of ${info.apkSize} bytes). Try again.")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (expectedSha != null && !expectedSha.equals(actual, ignoreCase = true)) {
                    throw UpdateException("The downloaded file is corrupt (SHA-256 mismatch). It was deleted.")
                }
            }
        } catch (e: IOException) {
            part.delete()
            throw UpdateException(GitHub.describeNetworkError(e))
        } catch (e: UpdateException) {
            part.delete()
            throw e
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw UpdateException("Could not save the downloaded file.")
        }
        return target
    }

    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun certHashes(info: PackageInfo): Set<String> {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo
            when {
                si == null -> null
                si.hasMultipleSigners() -> si.apkContentsSigners
                else -> si.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return sigs?.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.toSet() ?: emptySet()
    }

    fun verify(context: Context, file: File, info: UpdateInfo): String {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val archive = pm.getPackageArchiveInfo(file.path, signingFlag())
            ?: throw UpdateException("The downloaded file is not a valid APK.")
        if (archive.packageName != context.packageName) {
            throw UpdateException("The APK belongs to a different app (${archive.packageName}).")
        }
        val installed = pm.getPackageInfo(context.packageName, signingFlag())
        val newCode = PackageInfoCompat.getLongVersionCode(archive)
        val curCode = PackageInfoCompat.getLongVersionCode(installed)
        if (newCode <= curCode) {
            throw UpdateException("The APK (build $newCode) is not newer than the installed build ($curCode).")
        }
        val minSdk = archive.applicationInfo?.minSdkVersion ?: 0
        if (minSdk > Build.VERSION.SDK_INT) {
            throw UpdateException("This update needs Android API $minSdk or newer. Your phone runs API ${Build.VERSION.SDK_INT}.")
        }
        val a = certHashes(archive)
        val b = certHashes(installed)
        if (a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isEmpty()) {
            throw UpdateException(
                "The update is signed with a different key than the installed app, so Android will refuse it. " +
                    "Uninstall the app once and install this version (your settings will be reset)."
            )
        }
        val integrity = if (info.sha256 != null || info.sha256Url != null) "SHA-256 verified" else "size verified"
        return "$integrity, same app, build $newCode"
    }

    fun install(context: Context, apk: File): InstallResult {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return InstallResult.NEEDS_PERMISSION
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return InstallResult.STARTED
    }
}
