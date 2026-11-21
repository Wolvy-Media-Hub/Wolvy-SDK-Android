package net.wolvy.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDataTest {
    /** The shape app_render_video answers (Wolvy-Video-APIs/app_render_video.php). */
    private val answer = JSONObject("""
        {"status":"success","platform":"sdk","session_id":"0123456789abcdef0123456789abcdef",
         "token":"lic.tok/en+","expires_in":300,"poster":"https://img/p.jpg","server_id":"25001","duration":"613.4",
         "captions":[{"label":"Arabic","language_code":"ar"},{"label":"","language_code":"en"},{"label":"x"}],
         "chapters":[{"start":120,"end":300,"title":"B"},{"start":0,"end":120,"title":"A"}],
         "moments":[{"timestamp":42.5,"label":"Key point"}],
         "cdn_token":"cdnTok","cdn_exp":1790000000,"drm":{"mode":"drm"},
         "player_color":"#12ab9F","player_settings":null,"viewer_id":"user-8813","viewer_verified":true,
         "watermark":{"enabled":true,"mode":"static","position":"top-left","opacity":140},
         "app_only_l3":true}
    """.trimIndent())
    private val video = WolvyVideo("vid_1", 42, "vt")

    @Test fun readsEveryField() {
        val d = PlaybackData.fromJson(answer)
        assertEquals("0123456789abcdef0123456789abcdef", d.sessionId)
        assertEquals("lic.tok/en+", d.licenceToken)
        assertEquals(300, d.licenceTokenTtlSeconds)
        assertEquals("https://wolvy.net/api/process_videos/widevine?token=lic.tok%2Fen%2B", d.licenceUrl)
        assertEquals("25001", d.serverId)
        assertEquals(613.4, d.duration!!, 0.0)
        assertEquals(listOf(CaptionTrack("ar", "Arabic"), CaptionTrack("en", "en")), d.captions)  // no code: dropped
        assertEquals(listOf("A", "B"), d.chapters.map { it.title })                              // sorted
        assertEquals(Moment(42.5, "Key point"), d.moments.single())
        assertEquals(0xFF12AB9F.toInt(), d.playerColor)
        assertEquals("user-8813", d.viewerId)
        assertEquals(WatermarkConfig(true, "static", "top-left", 100), d.watermark)             // opacity clamped
        assertTrue(d.appOnly)
    }

    @Test fun cdnHeadersAndUrls() {
        val d = PlaybackData.fromJson(answer)
        assertEquals(
            mapOf("X-CDN-Token" to "cdnTok", "X-CDN-Exp" to "1790000000", "X-CDN-Session" to "0123456789abcdef0123456789abcdef"),
            d.cdnHeaders,
        )
        assertEquals("https://25001.wolvy-cdn.net/42/vid_1/dash/manifest.mpd?token=cdnTok&exp=1790000000", d.manifestUrl(video))
        assertEquals("https://25001.wolvy-cdn.net/42/vid_1/captions/ar.vtt", d.captionUrl(video, "ar"))
    }

    @Test fun anotherApiBaseForTheLicence() {
        assertEquals("https://example.test/api/process_videos/widevine?token=t",
            PlaybackData.fromJson(JSONObject("""{"token":"t","server_id":"1"}"""), "https://example.test/api/").licenceUrl)
    }

    @Test fun defaultsWhenOptionalFieldsAreMissing() {
        val d = PlaybackData.fromJson(JSONObject("""{"token":"t","server_id":"1","player_color":"red","duration":"","expires_in":0}"""))
        assertNull(d.sessionId); assertNull(d.cdnToken); assertNull(d.playerColor); assertNull(d.duration)
        assertEquals(300, d.licenceTokenTtlSeconds)
        assertEquals(WatermarkConfig(false, "moving", "bottom-right", 40), d.watermark)
        assertFalse(d.appOnly)
        assertTrue(d.cdnHeaders.isEmpty())
        assertEquals("https://1.wolvy-cdn.net/42/vid_1/dash/manifest.mpd", d.manifestUrl(video))
    }

    @Test fun refusesAnAnswerWithoutTokenOrServer() {
        val e = assertThrows(WolvyError::class.java) { PlaybackData.fromJson(JSONObject("""{"server_id":"1"}""")) }
        assertEquals(WolvyError.Code.FAILED, e.code)
    }
}
