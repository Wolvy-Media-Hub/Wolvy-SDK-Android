package net.wolvy.player.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same numbers as the web player (worker.js progress()/tick()). */
class ProgressTrackerTest {
    @Test fun continuousPlaybackMarksEverySecondOnce() {
        val p = ProgressTracker()
        var t = 0.0
        while (t <= 10.0) { p.tick(t, 100.0, playing = true); t += 0.25 }
        assertEquals(11, p.seconds)            // seconds 0..10
        assertEquals(11.0, p.percent(100.0), 0.0)
        // Replaying the same part adds nothing.
        t = 0.0
        while (t <= 10.0) { p.tick(t, 100.0, playing = true); t += 0.25 }
        assertEquals(11, p.seconds)
    }

    @Test fun aSeekMarksOnlyTheSecondPlayedNow() {
        val p = ProgressTracker()
        p.tick(1.0, 100.0, true)
        p.tick(50.2, 100.0, true)              // jump forward 49 s: not 2..49
        assertEquals(setOf(1, 50).size, p.seconds)
        p.tick(51.9, 100.0, true)              // 1.7 s step: fills 50..51
        assertEquals(3, p.seconds)
    }

    @Test fun pausedOrUnknownDurationMarksNothing() {
        val p = ProgressTracker()
        p.tick(5.0, 100.0, playing = false)
        p.tick(5.0, 0.0, playing = true)
        assertEquals(0, p.seconds)
        assertEquals(0.0, p.percent(0.0), 0.0)
    }

    @Test fun percentHasOneDecimalAndUsesWholeSeconds() {
        val p = ProgressTracker()
        p.tick(0.0, 3.9, true)                 // total = floor(3.9) = 3
        assertEquals(33.3, p.percent(3.9), 0.0)
        p.tick(1.0, 3.9, true); p.tick(2.0, 3.9, true); p.tick(3.0, 3.9, true)
        assertEquals(100.0, p.percent(3.9), 0.0)  // capped
    }

    @Test fun emitsWhenThePercentChangesAtMostOncePerSecond() {
        val p = ProgressTracker()
        p.tick(0.0, 10.0, true)
        assertTrue(p.shouldEmit(10.0, 5_000, force = false))
        assertFalse(p.shouldEmit(10.0, 5_100, force = false))   // same percent
        p.tick(0.5, 10.0, true); p.tick(1.2, 10.0, true)
        assertFalse(p.shouldEmit(10.0, 5_500, force = false))   // changed, but < 1 s
        assertTrue(p.shouldEmit(10.0, 6_001, force = false))
        p.tick(2.0, 10.0, true)
        assertTrue(p.shouldEmit(10.0, 6_100, force = true))     // the end forces it
    }
}
