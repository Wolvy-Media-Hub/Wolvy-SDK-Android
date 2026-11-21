package net.wolvy.player.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureFlagCounterTest {
    private val flags = HashMap<String, Boolean>()
    private val counter = SecureFlagCounter<String>(
        isSet = { flags[it] == true },
        set = { flags[it] = true },
        clear = { flags[it] = false },
    )

    @Test fun aNewViewBeforeTheOldOneLeavesKeepsTheWindowProtected() {
        counter.acquire("w")                 // the old view
        counter.acquire("w")                 // the new view (fullscreen, a new layout)
        counter.release("w")                 // the old view leaves
        assertTrue(flags["w"] == true)       // still protected: the new view is showing the video
        counter.release("w")
        assertFalse(flags["w"] == true)
    }

    @Test fun aFlagTheAppSetItselfIsNeverCleared() {
        flags["w"] = true                    // the app protects its window on its own
        counter.acquire("w")
        counter.release("w")
        assertTrue(flags["w"] == true)
    }

    @Test fun windowsAreCountedApartAndExtraReleasesAreHarmless() {
        counter.acquire("a")
        counter.acquire("b")
        counter.release("a")
        counter.release("a")
        assertFalse(flags["a"] == true)
        assertTrue(flags["b"] == true)
        assertEquals(1, counter.holders("b"))
    }
}
