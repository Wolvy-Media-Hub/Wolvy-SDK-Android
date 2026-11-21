package net.wolvy.player.internal

import net.wolvy.player.internal.RecoveryPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    @Test fun drmErrorWithAFreshTokenStepsDownOnceWithoutReload() {
        val r = RecoveryPolicy()
        assertEquals(Action.STEP_DOWN, r.onError(isDrm = true, tokenFresh = true))
        assertEquals(Action.RELOAD, r.onError(isDrm = true, tokenFresh = true))
        assertEquals(1, r.reloads)
    }

    @Test fun drmErrorWithAStaleTokenReloadsAndStepsDown() {
        val r = RecoveryPolicy()
        assertEquals(Action.RELOAD_AND_STEP_DOWN, r.onError(isDrm = true, tokenFresh = false))
        assertTrue(r.steppedDown)
    }

    @Test fun threeReloadsThenFail() {
        val r = RecoveryPolicy()
        repeat(3) { assertEquals(Action.RELOAD, r.onError(isDrm = false, tokenFresh = true)) }
        assertEquals(Action.FAIL, r.onError(isDrm = false, tokenFresh = true))
    }

    @Test fun aMinuteOfGoodPlaybackAfterAReloadAllowsMore() {
        val r = RecoveryPolicy()
        repeat(3) { r.onError(isDrm = false, tokenFresh = true) }
        r.onReload(100.0)
        r.onPosition(150.0)
        assertEquals(3, r.reloads)
        r.onPosition(160.5)
        assertEquals(0, r.reloads)
        assertEquals(Action.RELOAD, r.onError(isDrm = false, tokenFresh = true))
    }

    @Test fun tokenFreshUntil30SecondsBeforeItEnds() {
        assertTrue(RecoveryPolicy.tokenFresh(issuedAtMs = 1_000, ttlSeconds = 300, nowMs = 1_000 + 269_000))
        assertFalse(RecoveryPolicy.tokenFresh(issuedAtMs = 1_000, ttlSeconds = 300, nowMs = 1_000 + 270_000))
        assertTrue(RecoveryPolicy.tokenFresh(issuedAtMs = 1_000, ttlSeconds = 20, nowMs = 1_000 + 29_000)) // at least 30 s
        assertFalse(RecoveryPolicy.tokenFresh(issuedAtMs = 0, ttlSeconds = 300, nowMs = 10))
    }
}
