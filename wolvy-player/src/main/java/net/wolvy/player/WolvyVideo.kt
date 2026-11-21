package net.wolvy.player

import net.wolvy.player.internal.Base64Lenient
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * The three values a playback needs, the same ones the embed URL and the deep link carry.
 *
 * @property videoId the video's id.
 * @property accountId your Wolvy account id.
 * @property viewerToken the signed viewer token (`vt`). Make it on **your server**
 *   (`POST /v1/videos/{id}/playback-tokens` on the Developer API, or `signViewerToken` in the
 *   PHP and Node SDKs) and pass it to the app. Never put a signing secret in the app.
 * @property tokenProvider optional: asked for a fresh token when this one is about to expire or
 *   is refused, so tokens can be short-lived. See [ViewerTokenProvider].
 */
public class WolvyVideo @JvmOverloads constructor(
    public val videoId: String,
    public val accountId: Long,
    public val viewerToken: String,
    public val tokenProvider: ViewerTokenProvider? = null,
) {
    init {
        require(ID.matches(videoId)) { "videoId must be 1 to 64 letters, digits, '-' or '_'." }
        require(accountId > 0) { "accountId must be a positive number." }
        require(viewerToken.isNotBlank()) { "viewerToken is required: sign it on your server." }
    }

    /**
     * When the token expires (Unix seconds), read from its payload; null when it cannot be read.
     * Not a verification: only Wolvy's server checks the signature.
     */
    public val tokenExpiresAt: Long?
        get() = try {
            val payload = Base64Lenient.decode(viewerToken.substringBefore('.'))?.toString(Charsets.UTF_8)
            payload?.let { JSONObject(it).optLong("exp", 0L) }?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }

    /** The same video with another token (and the same provider). */
    internal fun withToken(token: String): WolvyVideo = WolvyVideo(videoId, accountId, token, tokenProvider)

    /** No token: safe to log. */
    override fun toString(): String = "WolvyVideo(videoId=$videoId, accountId=$accountId)"

    public companion object {
        private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        /**
         * Reads the three values from an embed URL with a viewer token, as
         * `POST /v1/videos/{id}/playback-tokens` returns it in `embed_url`:
         * `https://embed.wolvy.stream/{videoId}/{base64 accountId}?vt=…`.
         *
         * @param tokenProvider optional, see [ViewerTokenProvider].
         * @throws IllegalArgumentException when it is not such a URL.
         */
        @JvmStatic
        @JvmOverloads
        public fun fromEmbedUrl(url: String, tokenProvider: ViewerTokenProvider? = null): WolvyVideo {
            val uri = try { URI(url.trim()) } catch (e: Exception) {
                throw IllegalArgumentException("Not a URL.", e)
            }
            val parts = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
            require(parts.size >= 2) { "Expected https://embed.wolvy.stream/{videoId}/{base64 accountId}?vt=…" }
            val account = Base64Lenient.decode(URLDecoder.decode(parts[1], "UTF-8"))
                ?.toString(Charsets.UTF_8)?.trim()?.toLongOrNull()
                ?: throw IllegalArgumentException("The second part of the path is not a base64 account id.")
            val vt = (uri.rawQuery ?: "").split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it[0] == "vt" && it.size == 2 }
                ?.let { URLDecoder.decode(it[1], "UTF-8") }
                ?: throw IllegalArgumentException("The URL has no vt (viewer token).")
            return WolvyVideo(URLDecoder.decode(parts[0], "UTF-8"), account, vt, tokenProvider)
        }
    }
}
