package net.wolvy.player.internal

import net.wolvy.player.PlaybackData
import net.wolvy.player.PlaybackSource
import net.wolvy.player.WolvyError
import net.wolvy.player.WolvyVideo

/**
 * Asks the [PlaybackSource], refreshing the viewer token through the video's
 * [net.wolvy.player.ViewerTokenProvider] when it is about to expire, and once more when the
 * server refuses it. Runs on the player's background thread. Returns the video with the token
 * that worked, so the next reload uses it.
 */
internal class PlaybackFetcher(private val source: PlaybackSource) {
    companion object {
        /** Refresh when the token has this little time left: covers the request and a slow network. */
        const val REFRESH_MARGIN_SECONDS: Long = 60

        fun needsRefresh(video: WolvyVideo, nowSeconds: Long): Boolean {
            if (video.tokenProvider == null) return false
            val exp = video.tokenExpiresAt ?: return false
            return exp - nowSeconds <= REFRESH_MARGIN_SECONDS
        }
    }

    class Result(val video: WolvyVideo, val data: PlaybackData)

    @Throws(WolvyError::class)
    fun fetch(video: WolvyVideo, sessionId: String?, nowSeconds: Long): Result {
        var current = video
        var refreshed = false
        if (needsRefresh(current, nowSeconds)) {
            current = refresh(current)
            refreshed = true
        }
        return try {
            Result(current, source.fetch(current, sessionId))
        } catch (e: WolvyError) {
            if (e.reason != "viewer_token_required" || current.tokenProvider == null || refreshed) throw e
            current = refresh(current)
            Result(current, source.fetch(current, sessionId))
        }
    }

    private fun refresh(video: WolvyVideo): WolvyVideo {
        val provider = video.tokenProvider ?: return video
        val token = try {
            provider.fetchViewerToken(video)
        } catch (e: Exception) {
            throw WolvyError(WolvyError.Code.NETWORK_ERROR, "A fresh viewer token could not be fetched.",
                "token_provider_failed", ScrubbedCause(Scrub.summary(e)))
        }
        if (token.isNullOrBlank()) {
            throw WolvyError(WolvyError.Code.PLAYBACK_ERROR, "No fresh viewer token was given.", "viewer_token_required")
        }
        return video.withToken(token.trim())
    }
}
