package net.wolvy.player.internal

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Seconds actually played, each counted once: the same rule and the same numbers as the web
 * player (Wolvy-Worker-Script worker.js, progress()/tick()), so a course that completes at 90 %
 * on the web completes at 90 % in an app.
 *
 * Call [tick] about every 250 ms with the position; a forward step of at most 2 s marks every
 * second in between, anything else (a seek, a jump back) marks only the second played now.
 */
internal class ProgressTracker {
    private val watched = HashSet<Int>()
    private var lastPos = -1.0
    private var lastPercent = -1.0
    private var lastSentAtMs = 0L

    val seconds: Int get() = watched.size

    fun tick(position: Double, duration: Double, playing: Boolean) {
        if (playing && duration > 0) {
            if (lastPos >= 0 && position >= lastPos && position - lastPos <= 2) {
                var s = floor(lastPos).toInt()
                val end = floor(position).toInt()
                while (s <= end) { mark(s); s++ }
            } else {
                mark(floor(position).toInt())
            }
            lastPos = position
        } else {
            lastPos = -1.0
        }
    }

    /** Percent with one decimal, 0 to 100, against the whole seconds of [duration]. */
    fun percent(duration: Double): Double {
        if (duration <= 0) return 0.0
        val total = max(1, floor(duration).toInt())
        return min(100.0, (watched.size.toDouble() / total * 1000).roundToLong() / 10.0)
    }

    /**
     * Whether a progress event is due: the percent changed, and a second has passed since the
     * last one, unless [force] (the end) or the video is complete.
     */
    fun shouldEmit(duration: Double, nowMs: Long, force: Boolean): Boolean {
        val p = percent(duration)
        if (p == lastPercent) return false
        if (!force && p < 100 && nowMs - lastSentAtMs < 1000) return false
        lastPercent = p
        lastSentAtMs = nowMs
        return true
    }

    fun reset() {
        watched.clear(); lastPos = -1.0; lastPercent = -1.0; lastSentAtMs = 0L
    }

    private fun mark(s: Int) { if (s >= 0) watched.add(s) }
}
