package ir.mmdkavir.smartattendance.util

/** Decides whether a tap on the work-exit widget is a repeat of one that is already sent or still queued. */
object ExitGuard {
    const val WINDOW_MS = 60_000L

    fun isDuplicate(now: Long, lastTap: Long, exitQueued: Boolean): Boolean =
        exitQueued || (lastTap > 0L && now >= lastTap && now - lastTap < WINDOW_MS)
}
