package net.wolvy.player

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Everything the player needs to play one video, as the playback endpoint answers it.
 *
 * A custom [PlaybackSource] builds it with [fromJson] from an answer of the same shape.
 */
public class PlaybackData(
    /** The playback session. Sent on every media request (X-CDN-Session), and back when asking again. */
    public val sessionId: String?,
    /** The licence token, valid [licenceTokenTtlSeconds] seconds. */
    public val licenceToken: String,
    public val licenceTokenTtlSeconds: Int,
    /** Where the Widevine licence is requested, with the token. */
    public val licenceUrl: String,
    /** The media server, e.g. "25001". */
    public val serverId: String,
    public val cdnToken: String?,
    public val cdnExpires: Long?,
    public val poster: String?,
    public val duration: Double?,
    public val captions: List<CaptionTrack>,
    public val chapters: List<Chapter>,
    public val moments: List<Moment>,
    /** The account's player colour as 0xRRGGBB with full alpha, or null. */
    public val playerColor: Int?,
    /** The verified viewer id from the token, for the watermark. */
    public val viewerId: String?,
    public val watermark: WatermarkConfig,
    /** App-Only is on for the account: a software-DRM device gets no keys. */
    public val appOnly: Boolean,
) {
    /** Headers for every request to the CDN. */
    public val cdnHeaders: Map<String, String>
        get() = buildMap {
            if (!cdnToken.isNullOrEmpty()) {
                put("X-CDN-Token", cdnToken)
                cdnExpires?.let { put("X-CDN-Exp", it.toString()) }
            }
            if (!sessionId.isNullOrEmpty()) put("X-CDN-Session", sessionId)
        }

    /** The DASH manifest of [video] on this media server. */
    public fun manifestUrl(video: WolvyVideo): String {
        val q = if (!cdnToken.isNullOrEmpty()) "?token=${enc(cdnToken)}&exp=${cdnExpires ?: ""}" else ""
        return "https://$serverId.$CDN_DOMAIN/${video.accountId}/${video.videoId}/dash/manifest.mpd$q"
    }

    /** The WebVTT file of one caption language. */
    public fun captionUrl(video: WolvyVideo, language: String): String =
        "https://$serverId.$CDN_DOMAIN/${video.accountId}/${video.videoId}/captions/${enc(language)}.vtt"

    public companion object {
        public const val CDN_DOMAIN: String = "wolvy-cdn.net"
        public const val DEFAULT_API_BASE: String = "https://wolvy.net/api"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /**
         * Reads an answer of the playback endpoint (`app_render_video`, or `mobile_render_video`
         * for Wolvy Player). [apiBase] is where the licence proxy lives.
         *
         * @throws WolvyError when a required field is missing.
         */
        @JvmStatic
        @JvmOverloads
        public fun fromJson(json: JSONObject, apiBase: String = DEFAULT_API_BASE): PlaybackData {
            val token = json.optString("token")
            val server = json.optString("server_id")
            if (token.isEmpty() || server.isEmpty()) {
                throw WolvyError(WolvyError.Code.FAILED, "The playback answer has no licence token or media server.", "bad_answer")
            }
            val wm = json.optJSONObject("watermark")
            return PlaybackData(
                sessionId = json.optString("session_id").ifEmpty { null },
                licenceToken = token,
                licenceTokenTtlSeconds = json.optInt("expires_in", 300).takeIf { it > 0 } ?: 300,
                licenceUrl = "${apiBase.trimEnd('/')}/process_videos/widevine?token=${enc(token)}",
                serverId = server,
                cdnToken = json.optString("cdn_token").ifEmpty { null },
                cdnExpires = if (json.has("cdn_exp") && !json.isNull("cdn_exp")) json.optLong("cdn_exp") else null,
                poster = json.optString("poster").ifEmpty { null },
                duration = json.optString("duration").toDoubleOrNull()?.takeIf { it > 0 },
                captions = list(json.optJSONArray("captions")) {
                    val lang = it.optString("language_code")
                    if (lang.isEmpty()) null else CaptionTrack(lang, it.optString("label").ifEmpty { lang })
                },
                chapters = list(json.optJSONArray("chapters")) {
                    Chapter(it.optDouble("start", 0.0), it.optDouble("end", 0.0), it.optString("title"))
                }.sortedBy { it.start },
                moments = list(json.optJSONArray("moments")) {
                    Moment(it.optDouble("timestamp", 0.0), it.optString("label"))
                },
                playerColor = color(json.optString("player_color")),
                viewerId = json.optString("viewer_id").ifEmpty { null },
                watermark = WatermarkConfig(
                    enabled = wm?.optBoolean("enabled") ?: false,
                    mode = wm?.optString("mode")?.ifEmpty { null } ?: "moving",
                    position = wm?.optString("position")?.ifEmpty { null } ?: "bottom-right",
                    opacity = (wm?.optInt("opacity", 40) ?: 40).coerceIn(0, 100),
                ),
                appOnly = json.optBoolean("app_only_l3"),
            )
        }

        private fun <T> list(a: JSONArray?, item: (JSONObject) -> T?): List<T> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(item) }
        }

        private fun color(hex: String?): Int? {
            if (hex == null || !Regex("^#[0-9A-Fa-f]{6}$").matches(hex)) return null
            return (0xFF000000 or hex.substring(1).toLong(16)).toInt()
        }
    }
}
