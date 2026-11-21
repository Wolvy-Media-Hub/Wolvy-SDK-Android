package net.wolvy.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class WolvyVideoTest {
    @Test fun readsTheEmbedUrlThatTheApiReturns() {
        val v = WolvyVideo.fromEmbedUrl("https://embed.wolvy.stream/a1b2c3d4e5f60718293a/NDI=?vt=eyJ2aWQiOiJ1c2VyLTg4MTMiLCJleHAiOjE3OTAwMDAwMDB9.XBNW%2Bsig")
        assertEquals("a1b2c3d4e5f60718293a", v.videoId)
        assertEquals(42L, v.accountId)
        assertEquals("eyJ2aWQiOiJ1c2VyLTg4MTMiLCJleHAiOjE3OTAwMDAwMDB9.XBNW+sig", v.viewerToken)
    }

    @Test fun acceptsUnpaddedAndUrlEncodedAccountIds() {
        assertEquals(42L, WolvyVideo.fromEmbedUrl("https://embed.wolvy.stream/vid1/NDI?vt=x").accountId)
        assertEquals(42L, WolvyVideo.fromEmbedUrl("https://embed.wolvy.stream/vid1/NDI%3D?vt=x").accountId)
    }

    @Test fun refusesUrlsWithoutTheThreeValues() {
        assertThrows(IllegalArgumentException::class.java) { WolvyVideo.fromEmbedUrl("https://embed.wolvy.stream/vid1/NDI=") }
        assertThrows(IllegalArgumentException::class.java) { WolvyVideo.fromEmbedUrl("https://embed.wolvy.stream/vid1?vt=x") }
        assertThrows(IllegalArgumentException::class.java) { WolvyVideo.fromEmbedUrl("https://embed.wolvy.stream/vid1/bm90IGEgbnVtYmVy?vt=x") }
        assertThrows(IllegalArgumentException::class.java) { WolvyVideo.fromEmbedUrl("not a url at all %%") }
    }

    @Test fun validatesItsValues() {
        assertThrows(IllegalArgumentException::class.java) { WolvyVideo("bad id!", 1, "vt") }
        assertThrows(IllegalArgumentException::class.java) { WolvyVideo("vid", 0, "vt") }
        assertThrows(IllegalArgumentException::class.java) { WolvyVideo("vid", 1, " ") }
    }

    @Test fun keepsTheProviderFromTheEmbedUrlAndWithToken() {
        val provider = ViewerTokenProvider { "fresh" }
        val v = WolvyVideo.fromEmbedUrl("https://embed.wolvy.stream/vid1/NDI=?vt=x", provider)
        val w = v.withToken("y")
        assertEquals("y", w.viewerToken)
        assertEquals(provider, w.tokenProvider)
        assertEquals(v.videoId, w.videoId)
    }

    @Test fun toStringNeverShowsTheToken() {
        assertFalse(WolvyVideo("vid", 7, "secret-token-value").toString().contains("secret"))
    }
}
