package net.wolvy.player.internal

/**
 * What to do when playback fails, ported from Wolvy Player (PlayerScreen.tsx onVideoError):
 *
 * 1. A DRM error while the licence token is still fresh, the first time: cap to SD and retry,
 *    with no reload. On a software-DRM device the licence holds the SD key but not the HD ones.
 * 2. Otherwise (a stale licence token, a network drop, a CDN token that ran out): fetch fresh
 *    playback data for the same session and resume at the same position, up to [maxReloads]
 *    times; a DRM error also steps down if it has not yet.
 * 3. Then give up and report the error.
 *
 * The reload count starts again after a minute of good playback, so a long video can recover
 * from occasional errors.
 */
internal class RecoveryPolicy(private val maxReloads: Int = 3) {
    enum class Action { STEP_DOWN, RELOAD, RELOAD_AND_STEP_DOWN, FAIL }

    var steppedDown: Boolean = false
        private set
    var reloads: Int = 0
        private set
    private var reloadBase = 0.0

    fun onError(isDrm: Boolean, tokenFresh: Boolean): Action {
        if (isDrm && !steppedDown && tokenFresh) {
            steppedDown = true
            return Action.STEP_DOWN
        }
        if (reloads < maxReloads) {
            reloads++
            if (isDrm && !steppedDown) {
                steppedDown = true
                return Action.RELOAD_AND_STEP_DOWN
            }
            return Action.RELOAD
        }
        return Action.FAIL
    }

    /** Where the last reload resumed, in seconds. */
    fun onReload(position: Double) { reloadBase = position }

    fun onPosition(position: Double) {
        if (reloads > 0 && position > reloadBase + 60) reloads = 0
    }

    /** A manual retry starts the count again. */
    fun reset() { reloads = 0; reloadBase = 0.0 }

    companion object {
        /** A licence token is "fresh" until 30 s before it ends (at least 30 s), as in Wolvy Player. */
        fun tokenFresh(issuedAtMs: Long, ttlSeconds: Int, nowMs: Long): Boolean {
            if (issuedAtMs <= 0) return false
            val windowMs = maxOf(30, ttlSeconds - 30) * 1000L
            return nowMs - issuedAtMs < windowMs
        }
    }
}
