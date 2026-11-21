package net.wolvy.player.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmallPartsTest {
    @Test fun base64StandardUrlSafeWithAndWithoutPadding() {
        assertArrayEquals("42".toByteArray(), Base64Lenient.decode("NDI="))
        assertArrayEquals("42".toByteArray(), Base64Lenient.decode("NDI"))
        assertArrayEquals(byteArrayOf(-5, -1), Base64Lenient.decode("-_8"))   // URL-safe
        assertArrayEquals(byteArrayOf(-5, -1), Base64Lenient.decode("+/8="))  // standard
        assertNull(Base64Lenient.decode("N"))
        assertNull(Base64Lenient.decode("N!I="))
        assertNull(Base64Lenient.decode(""))
    }

    @Test fun movingWatermarkStaysInTheWebRanges() {
        val r = Random(7)
        repeat(2000) {
            val s = WatermarkPlacer.place("moving", "bottom-right", r) as WatermarkPlacer.Spot.Moving
            assertTrue(s.left in 0.02..0.78)
            assertTrue(s.top in 0.04..0.85)
        }
    }

    @Test fun staticWatermarkCorners() {
        assertEquals(WatermarkPlacer.Spot.Corner(top = true, left = true), WatermarkPlacer.place("static", "top-left"))
        assertEquals(WatermarkPlacer.Spot.Corner(top = false, left = false), WatermarkPlacer.place("static", ""))
        assertEquals(WatermarkPlacer.Spot.Corner(top = false, left = true), WatermarkPlacer.place("static", "bottom-left"))
    }

    @Test fun errorKinds() {
        assertTrue(ErrorKind.isDrm(6004, ""))
        assertTrue(ErrorKind.isDrm(4003, ""))                     // decoding failed: a missing key
        assertTrue(ErrorKind.isDrm(1000, "android.media.MediaCodec\$CryptoException no key"))
        assertFalse(ErrorKind.isDrm(2004, "HTTP 403"))
        assertTrue(ErrorKind.isNetwork(2004))
        assertFalse(ErrorKind.isNetwork(6004))
    }
}
