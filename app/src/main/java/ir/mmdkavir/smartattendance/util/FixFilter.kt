package ir.mmdkavir.smartattendance.util

/** A location fix reduced to what the stability rules need (testable without Android classes). */
data class Fix(val lat: Double, val lng: Double, val accuracy: Float, val elapsedMs: Long)

/**
 * Decides whether a new fix may replace the current one, so the position marker neither starts
 * from an old/inaccurate point nor jumps between fixes of different quality.
 */
object FixFilter {
    const val MAX_AGE_MS = 20_000L
    const val MAX_ACCURACY_M = 150f
    private const val STALE_CURRENT_MS = 15_000L

    fun valid(f: Fix, nowMs: Long): Boolean =
        f.accuracy > 0f && f.accuracy <= MAX_ACCURACY_M &&
            f.lat in -90.0..90.0 && f.lng in -180.0..180.0 &&
            !(f.lat == 0.0 && f.lng == 0.0) &&
            nowMs - f.elapsedMs <= MAX_AGE_MS

    fun accept(current: Fix?, new: Fix, nowMs: Long): Boolean {
        if (!valid(new, nowMs)) return false
        if (current == null || nowMs - current.elapsedMs > STALE_CURRENT_MS) return true
        if (new.elapsedMs <= current.elapsedMs) return false
        val moved = Geo.distanceMeters(current.lat, current.lng, new.lat, new.lng)
        if (new.accuracy < current.accuracy * 0.8f) return true
        // A real move, reported with comparable quality (never a much worse fix replacing a good one).
        return moved > current.accuracy + new.accuracy && new.accuracy <= maxOf(current.accuracy * 2f, 30f)
    }
}
