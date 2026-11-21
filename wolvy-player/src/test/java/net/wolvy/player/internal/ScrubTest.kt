package net.wolvy.player.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrubTest {
    @Test fun removesTokensFromAddresses() {
        assertEquals(
            "https://25001.wolvy-cdn.net/42/v/dash/manifest.mpd?token=[removed]&exp=[removed]",
            Scrub.text("https://25001.wolvy-cdn.net/42/v/dash/manifest.mpd?token=AbC-12_x&exp=1790000000"),
        )
        assertEquals(
            "https://wolvy.net/api/process_videos/widevine?token=[removed]",
            Scrub.text("https://wolvy.net/api/process_videos/widevine?token=eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjF9.sig"),
        )
    }

    @Test fun removesJwtLikeTokensAnywhere() {
        val s = Scrub.text("viewer eyJ2aWQiOiJ1c2VyLTEifQ.XBNWyMiuLw0y7WlQ refused; licence eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln")
        assertFalse(s.contains("eyJ"))
        assertEquals("viewer [token] refused; licence [token]", s)
    }

    @Test fun removesTokenHeaders() {
        assertEquals("{X-CDN-Token=[removed], X-CDN-Exp: [removed], X-CDN-Session=abc}",
            Scrub.text("{X-CDN-Token=abc123, X-CDN-Exp: 1790000000, X-CDN-Session=abc}"))
    }

    @Test fun leavesOrdinaryTextAlone() {
        assertEquals("Response code: 403", Scrub.text("Response code: 403"))
    }

    @Test fun summarisesTheCauseChainScrubbed() {
        val e = RuntimeException("Source error", java.io.IOException("Unable to connect to https://1.wolvy-cdn.net/a.mpd?token=secret"))
        val s = Scrub.summary(e)
        assertEquals("RuntimeException: Source error <- IOException: Unable to connect to https://1.wolvy-cdn.net/a.mpd?token=[removed]", s)
        assertTrue(ScrubbedCause(s).stackTrace.isEmpty())
    }
}
