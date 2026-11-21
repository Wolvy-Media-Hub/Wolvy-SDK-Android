package net.wolvy.player.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityPolicyTest {
    private val ladder = listOf(240, 1080, 480, 720, 360, 720)

    @Test fun hardwareDrmGetsTheFullLadder() {
        val q = QualityPolicy(softwareDrm = false)
        assertEquals(Int.MAX_VALUE to 0, q.constraints())
        assertTrue(q.onTracks(ladder))
        assertEquals(listOf(1080, 720, 480, 360, 240), q.available)
        assertEquals(Int.MAX_VALUE to 0, q.constraints())
    }

    @Test fun softwareDrmIsCappedAtSdFromTheStart() {
        val q = QualityPolicy(softwareDrm = true)
        assertEquals(480 to 0, q.constraints())     // before the manifest is read
        assertTrue(q.onTracks(ladder))
        assertEquals(listOf(480, 360, 240), q.available)
        assertEquals(480 to 0, q.constraints())
        assertFalse(q.select(720))                  // not on offer
    }

    @Test fun softwareDrmWithNoSdRenditionCannotPlay() {
        val q = QualityPolicy(softwareDrm = true)
        assertFalse(q.onTracks(listOf(720, 1080)))
    }

    @Test fun pinningAHeightAndBackToAuto() {
        val q = QualityPolicy(softwareDrm = false)
        q.onTracks(ladder)
        assertTrue(q.select(720))
        assertEquals(720 to 720, q.constraints())
        assertTrue(q.select(null))
        assertNull(q.selected)
    }

    @Test fun stepDownCapsAnyDeviceAndDropsAnHdPin() {
        val q = QualityPolicy(softwareDrm = false)
        q.onTracks(ladder)
        q.select(1080)
        q.stepDown()
        assertNull(q.selected)
        assertEquals(listOf(480, 360, 240), q.available)
        assertEquals(480 to 0, q.constraints())
    }

    @Test fun capFollowsTheTallestSdHeightTheVideoHas() {
        val q = QualityPolicy(softwareDrm = true)
        q.onTracks(listOf(1080, 360, 240))
        assertEquals(360 to 0, q.constraints())
    }
}
