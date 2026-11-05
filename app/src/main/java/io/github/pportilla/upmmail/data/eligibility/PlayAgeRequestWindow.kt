package io.github.pportilla.upmmail.data.eligibility

/** Main-thread request sequencing, separate from Android so timeout/resume races are testable. */
internal class PlayAgeRequestWindow(private val cooldownMillis: Long = 30_000L) {
    private var generation = 0L
    private var checking = false
    private var lastFinishedAt: Long? = null

    fun begin(now: Long, force: Boolean): Long? {
        if (checking) return null
        val last = lastFinishedAt
        if (!force && last != null && now - last < cooldownMillis) return null
        checking = true
        return ++generation
    }

    // A timed-out request may still supply a meaningful restriction after the user closes a
    // Play dialog. It stays current until a newer request starts; a late result must not win
    // over that newer request, including a newer failure which preserves its prior decision.
    fun isCurrent(token: Long): Boolean = token == generation

    /** True only for the first completion/timeout, so UI callbacks and pending gates end once. */
    fun finish(token: Long, now: Long): Boolean {
        if (!isCurrent(token) || !checking) return false
        checking = false
        lastFinishedAt = now
        return true
    }
}
