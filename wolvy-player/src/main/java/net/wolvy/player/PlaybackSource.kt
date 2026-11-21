package net.wolvy.player

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Where playback data comes from. The default, [AppRenderVideoSource], asks
 * `https://wolvy.net/api/app_render_video`; another source can supply it differently (Wolvy
 * Player keeps its own attested endpoint).
 */
public fun interface PlaybackSource {
    /**
     * Returns playback data for [video]. Called on a background thread.
     *
     * @param sessionId set when the player asks again during the same viewing (a fresh licence
     *   token after an error, or after 5 minutes); send it back so the viewing stays one session.
     * @throws WolvyError when the video cannot be played.
     */
    @Throws(WolvyError::class)
    public fun fetch(video: WolvyVideo, sessionId: String?): PlaybackData
}

/**
 * The SDK's own source: `POST {apiBase}/app_render_video` with the app's package name.
 * The package name must be on the account's Allowed apps (Settings → Protection).
 *
 * @param appId the Android package name, sent as X-Wolvy-App-Id.
 * @param sdk the SDK's name and version, sent as X-Wolvy-SDK (wrappers send their own).
 */
public class AppRenderVideoSource @JvmOverloads constructor(
    private val appId: String,
    private val sdk: String = "wolvy-android/${BuildConfig.SDK_VERSION}",
    private val apiBase: String = PlaybackData.DEFAULT_API_BASE,
) : PlaybackSource {

    /** Uses the app's own package name. */
    public constructor(context: Context) : this(context.packageName)

    override fun fetch(video: WolvyVideo, sessionId: String?): PlaybackData {
        val conn = try {
            URL("${apiBase.trimEnd('/')}/app_render_video").openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw WolvyError(WolvyError.Code.NETWORK_ERROR, "Could not reach Wolvy.", "network", e)
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-Wolvy-Platform", "android")
            conn.setRequestProperty("X-Wolvy-App-Id", appId)
            conn.setRequestProperty("X-Wolvy-SDK", sdk)
            val body = buildString {
                append("user_id=").append(video.accountId)
                append("&video_id=").append(enc(video.videoId))
                append("&vt=").append(enc(video.viewerToken))
                if (!sessionId.isNullOrEmpty()) append("&session_id=").append(enc(sessionId))
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val text = (if (status < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) {
                throw WolvyError(
                    if (status >= 500) WolvyError.Code.NETWORK_ERROR else WolvyError.Code.FAILED,
                    "Wolvy answered HTTP $status without JSON.", "http_$status", e,
                )
            }
            if (json.optString("status") != "success") {
                throw WolvyError.fromServer(status, json.optString("code").ifEmpty { null }, json.optString("message"))
            }
            return PlaybackData.fromJson(json, apiBase)
        } catch (e: WolvyError) {
            throw e
        } catch (e: IOException) {
            throw WolvyError(WolvyError.Code.NETWORK_ERROR, "Could not reach Wolvy.", "network", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
