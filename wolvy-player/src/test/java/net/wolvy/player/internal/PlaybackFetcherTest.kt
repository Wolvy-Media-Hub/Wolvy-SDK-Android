package net.wolvy.player.internal

import net.wolvy.player.PlaybackData
import net.wolvy.player.PlaybackSource
import net.wolvy.player.ViewerTokenProvider
import net.wolvy.player.WolvyError
import net.wolvy.player.WolvyVideo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PlaybackFetcherTest {
    private val now = 1_800_000_000L

    /** A token shaped like the real ones: base64url({"vid","exp"}) + "." + a signature (not checked here). */
    private fun token(exp: Long, tag: String = "sig") =
        Base64.getUrlEncoder().withoutPadding().encodeToString("""{"vid":"user-1","exp":$exp}""".toByteArray()) + ".$tag"

    private val answer = PlaybackData.fromJson(JSONObject("""{"token":"lic","server_id":"1"}"""))

    /** Refuses tokens in [refused]; records the tokens it saw. */
    private class FakeSource(val refused: Set<String>, val answer: PlaybackData) : PlaybackSource {
        val seen = ArrayList<String>()
        override fun fetch(video: WolvyVideo, sessionId: String?): PlaybackData {
            seen.add(video.viewerToken)
            if (video.viewerToken in refused) throw WolvyError(WolvyError.Code.PLAYBACK_ERROR, "no", "viewer_token_required")
            return answer
        }
    }

    private class FakeProvider(vararg tokens: String?) : ViewerTokenProvider {
        private val queue = ArrayDeque(tokens.toList())
        var calls = 0
        override fun fetchViewerToken(video: WolvyVideo): String? { calls++; return queue.removeFirstOrNull() }
    }

    @Test fun readsTheExpiryFromTheToken() {
        assertEquals(now + 300, WolvyVideo("v", 1, token(now + 300)).tokenExpiresAt)
        assertEquals(null, WolvyVideo("v", 1, "not-a-token").tokenExpiresAt)
    }

    @Test fun aTokenWithTimeLeftIsUsedAsIs() {
        val provider = FakeProvider("fresh")
        val source = FakeSource(emptySet(), answer)
        val r = PlaybackFetcher(source).fetch(WolvyVideo("v", 1, token(now + 600), provider), null, now)
        assertEquals(0, provider.calls)
        assertEquals(1, source.seen.size)
        assertSame(answer, r.data)
    }

    @Test fun aTokenAboutToExpireIsRefreshedFirst() {
        val fresh = token(now + 600, "fresh")
        val provider = FakeProvider(fresh)
        val source = FakeSource(emptySet(), answer)
        val r = PlaybackFetcher(source).fetch(WolvyVideo("v", 1, token(now + 30), provider), "s1", now)
        assertEquals(listOf(fresh), source.seen)        // the old token was never sent
        assertEquals(fresh, r.video.viewerToken)       // kept for the next reload
        assertSame(provider, r.video.tokenProvider)
    }

    @Test fun aRefusedTokenIsRefreshedOnceAndRetried() {
        val old = token(now + 600, "old")
        val fresh = token(now + 600, "fresh")
        val provider = FakeProvider(fresh)
        val source = FakeSource(setOf(old), answer)
        val r = PlaybackFetcher(source).fetch(WolvyVideo("v", 1, old, provider), null, now)
        assertEquals(listOf(old, fresh), source.seen)
        assertEquals(fresh, r.video.viewerToken)
    }

    @Test fun refusedTwiceIsAnError() {
        val old = token(now + 600, "old")
        val fresh = token(now + 600, "fresh")
        val source = FakeSource(setOf(old, fresh), answer)
        val e = assertThrows(WolvyError::class.java) {
            PlaybackFetcher(source).fetch(WolvyVideo("v", 1, old, FakeProvider(fresh, "third")), null, now)
        }
        assertEquals("viewer_token_required", e.reason)
        assertEquals(2, source.seen.size)               // no loop
    }

    @Test fun aRefreshedTokenThatIsRefusedIsNotRefreshedAgain() {
        val fresh = token(now + 600, "fresh")
        val provider = FakeProvider(fresh, "another")
        val source = FakeSource(setOf(fresh), answer)
        assertThrows(WolvyError::class.java) {
            PlaybackFetcher(source).fetch(WolvyVideo("v", 1, token(now + 10), provider), null, now)
        }
        assertEquals(1, provider.calls)
    }

    @Test fun withoutAProviderTheRefusalStands() {
        val old = token(now + 10)
        val source = FakeSource(setOf(old), answer)
        val e = assertThrows(WolvyError::class.java) { PlaybackFetcher(source).fetch(WolvyVideo("v", 1, old), null, now) }
        assertEquals("viewer_token_required", e.reason)
    }

    @Test fun otherRefusalsAreNotRetried() {
        val source = object : PlaybackSource {
            var calls = 0
            override fun fetch(video: WolvyVideo, sessionId: String?): PlaybackData {
                calls++; throw WolvyError(WolvyError.Code.PLAYBACK_ERROR, "no", "app_not_allowed")
            }
        }
        val provider = FakeProvider("fresh")
        assertThrows(WolvyError::class.java) { PlaybackFetcher(source).fetch(WolvyVideo("v", 1, token(now + 600), provider), null, now) }
        assertEquals(1, source.calls)
        assertEquals(0, provider.calls)
    }

    @Test fun aProviderWithNothingOrFailingIsReported() {
        val src = FakeSource(emptySet(), answer)
        val none = assertThrows(WolvyError::class.java) {
            PlaybackFetcher(src).fetch(WolvyVideo("v", 1, token(now + 5), FakeProvider(null)), null, now)
        }
        assertEquals("viewer_token_required", none.reason)
        val failing = ViewerTokenProvider { throw java.io.IOException("GET https://customer.test/token?vt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.abc failed") }
        val e = assertThrows(WolvyError::class.java) {
            PlaybackFetcher(src).fetch(WolvyVideo("v", 1, token(now + 5), failing), null, now)
        }
        assertEquals(WolvyError.Code.NETWORK_ERROR, e.code)
        assertEquals("token_provider_failed", e.reason)
        assertFalse(e.cause!!.message!!.contains("eyJ"))           // scrubbed
        assertTrue(src.seen.isEmpty())
    }

    @Test fun needsRefreshOnlyWithAProviderAndAReadableExpiry() {
        assertFalse(PlaybackFetcher.needsRefresh(WolvyVideo("v", 1, token(now)), now))
        assertTrue(PlaybackFetcher.needsRefresh(WolvyVideo("v", 1, token(now + 60), FakeProvider()), now))
        assertFalse(PlaybackFetcher.needsRefresh(WolvyVideo("v", 1, token(now + 61), FakeProvider()), now))
        assertFalse(PlaybackFetcher.needsRefresh(WolvyVideo("v", 1, "opaque", FakeProvider()), now))
    }
}
