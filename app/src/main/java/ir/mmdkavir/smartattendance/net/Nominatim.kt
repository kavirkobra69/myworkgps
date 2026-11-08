package ir.mmdkavir.smartattendance.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

data class Place(val name: String, val lat: Double, val lon: Double)

object Nominatim {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        try {
            val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", "6")
                .addQueryParameter("q", query)
                .build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "SmartAttendance/1.0 (Android)")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emptyList()
                } else {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    (0 until arr.length()).map { i ->
                        val o = arr.getJSONObject(i)
                        Place(o.optString("display_name"), o.getDouble("lat"), o.getDouble("lon"))
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
