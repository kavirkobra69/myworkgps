package ir.mmdkavir.smartattendance.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** In-app language. A Compose snapshot state, so every screen that calls t() recomposes when it changes. */
object Lang {
    var code by mutableStateOf("en")

    val isFa: Boolean get() = code == "fa"

    fun normalize(value: String?): String = when (value) {
        "fa", "en" -> value
        else -> if (Locale.getDefault().language == "fa") "fa" else "en"
    }
}

/** Translates an English UI string. Unknown strings fall back to English. */
fun t(en: String): String = if (Lang.isFa) Fa.map[en] ?: en else en

/** Translates and formats. Always uses Latin digits so numbers stay unchanged in both languages. */
fun t(en: String, vararg args: Any?): String = String.format(Locale.US, t(en), *args)
