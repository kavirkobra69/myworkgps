package ir.mmdkavir.smartattendance.net

import ir.mmdkavir.smartattendance.i18n.t
import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/** libsodium sealed box, the format GitHub requires for Actions secrets. */
object Sealer {
    private const val SEAL_BYTES = 48

    fun seal(publicKeyBase64: String, message: String): String {
        val key = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        if (key.size != 32) throw GhException(t("GitHub returned an invalid secrets public key"))
        val plain = message.toByteArray(Charsets.UTF_8)
        val cipher = ByteArray(plain.size + SEAL_BYTES)
        val ok = LazySodiumAndroid(SodiumAndroid()).cryptoBoxSeal(cipher, plain, plain.size.toLong(), key)
        if (!ok) throw GhException(t("Could not encrypt the secret on this device"))
        return Base64.encodeToString(cipher, Base64.NO_WRAP)
    }
}
