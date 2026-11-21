package net.wolvy.player

import android.content.Context
import net.wolvy.player.internal.AppAttestor
import net.wolvy.player.internal.AppSigning
import net.wolvy.player.internal.PlayIntegrityTokens
import net.wolvy.player.internal.WolvyHttp

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
 * Built from a [Context] (the default in [WolvyPlayer]), it also proves the app is genuine when
 * the account has set up app protection for it (the dashboard's Protection panel: the
 * customer's own Play Integrity). A proven app signs its requests and is treated like Wolvy
 * Player: with App-Only on, it plays on phones with software DRM too. Without the setup, or when
 * the proof fails, it plays unproven, under the same rules as a browser, unless the account only
 * allows proven apps.
 *
 * @param appId the Android package name, sent as X-Wolvy-App-Id.
 * @param sdk the SDK's name and version, sent as X-Wolvy-SDK (wrappers send their own).
 */
public class AppRenderVideoSource internal constructor(
    private val appId: String,
    private val sdk: String,
    private val apiBase: String,
    private val attestor: AppAttestor?,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : PlaybackSource {

    /** Without app protection: the app is never proven. */
    @JvmOverloads
    public constructor(
        appId: String,
        sdk: String = DEFAULT_SDK,
        apiBase: String = PlaybackData.DEFAULT_API_BASE,
    ) : this(appId, sdk, apiBase, null)

    /** Uses the app's own package name, and proves the app when its protection is set up. */
    @JvmOverloads
    public constructor(
        context: Context,
        sdk: String = DEFAULT_SDK,
        apiBase: String = PlaybackData.DEFAULT_API_BASE,
    ) : this(
        context.packageName, sdk, apiBase,
        AppAttestor(context.packageName, sdk, apiBase, PlayIntegrityTokens(context)),
    )

    private companion object {
        val DEFAULT_SDK = "wolvy-android/${BuildConfig.SDK_VERSION}"
        val KEY_REFUSED = setOf("app_key_expired", "app_key_invalid")
    }

    override fun fetch(video: WolvyVideo, sessionId: String?): PlaybackData =
        try {
            once(video, sessionId)
        } catch (e: WolvyError) {
            // Wolvy no longer takes the app key: prove the app again, once.
            if (attestor == null || e.reason !in KEY_REFUSED) throw e
            attestor.invalidate()
            once(video, sessionId)
        }

    private fun once(video: WolvyVideo, sessionId: String?): PlaybackData {
        val key = attestor?.keyFor(video.accountId)
        val headers = buildMap {
            put("X-Wolvy-Platform", "android")
            put("X-Wolvy-App-Id", appId)
            put("X-Wolvy-SDK", sdk)
            if (key != null) {
                val ts = clock()
                put("X-Wolvy-App-Key-Id", key.keyId)
                put("X-Wolvy-App-Timestamp", ts.toString())
                put("X-Wolvy-App-Signature", AppSigning.renderSignature(key.key, video.videoId, video.accountId, ts))
            }
        }
        val form = buildMap {
            put("user_id", video.accountId.toString())
            put("video_id", video.videoId)
            put("vt", video.viewerToken)
            if (!sessionId.isNullOrEmpty()) put("session_id", sessionId)
        }
        val answer = WolvyHttp.postForm("${apiBase.trimEnd('/')}/app_render_video", headers, form)
        val json = answer.json
        if (json.optString("status") != "success") {
            val code = json.optString("code").ifEmpty { null }
            // The account only allows proven apps, and Wolvy refused this app's proof: say why.
            if (code == "attestation_required") attestor?.lastRefusal?.let { throw it }
            throw WolvyError.fromServer(answer.status, code, json.optString("message"))
        }
        val data = PlaybackData.fromJson(json, apiBase)
        return if (key != null && data.attested) {
            val ts = clock()
            data.withLicenceHeaders(mapOf(
                "X-Wolvy-Licence-Timestamp" to ts.toString(),
                "X-Wolvy-Licence-Signature" to AppSigning.licenceSignature(key.key, data.licenceToken, ts),
            ))
        } else {
            data
        }
    }
}
