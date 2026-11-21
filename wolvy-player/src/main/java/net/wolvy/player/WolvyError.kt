package net.wolvy.player

/**
 * An error from the player. [code] is one of the web Player API's codes, so the same handling
 * works in every Wolvy SDK; [reason] says more, for logs and support.
 *
 * Reasons from the server are its own codes: `app_not_allowed` (add the app's package name in the
 * dashboard: Settings → Protection → Allowed apps), `viewer_token_required` (missing, expired or
 * wrongly signed token), `update_required` (this SDK version is too old), `video_not_found`,
 * `video_not_ready`, `multi_drm_required`, `ip_not_allowed`, `country_not_allowed`,
 * `viewer_link_locked`, `rate_limited`, `not_available`; with app protection, `attestation_required`
 * (the account only allows apps that prove themselves, and this one could not), and the reasons of
 * Wolvy's refusal of the app's proof: `device_compromised` (rooted phone, emulator, unlocked
 * bootloader; code [Code.DEVICE_NOT_SUPPORTED]), `app_not_genuine` (not the app as published on
 * Google Play), `attestation_failed`. Reasons from the device:
 * `screen_protection_unavailable`, `no_secure_quality`, `hardware_drm_required`, `licence_refused`,
 * `playback_failed`, `network`.
 */
public class WolvyError @JvmOverloads constructor(
    public val code: Code,
    message: String,
    public val reason: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** The web Player API's error codes, with the same meaning. */
    public enum class Code(public val value: String) {
        /** The video could not be loaded: no connection, or the server refused (see [reason]). */
        NETWORK_ERROR("network_error"),
        /** The video could not be played (see [reason]). */
        PLAYBACK_ERROR("playback_error"),
        /** App-Only is on for the account and this device has no hardware DRM. */
        DEVICE_NOT_SUPPORTED("device_not_supported"),
        /** The call needs the video loaded first (seek, setQuality). */
        NOT_READY("not_ready"),
        /** A setter got a value that is not on offer. */
        INVALID_ARGUMENT("invalid_argument"),
        /** release() was called. */
        DESTROYED("destroyed"),
        /** Something failed in a way the SDK did not name. */
        FAILED("failed"),
    }

    override fun toString(): String = "WolvyError(${code.value}${reason?.let { ", $it" } ?: ""}): $message"

    internal companion object {
        private val NETWORKISH = setOf("rate_limited", "not_available")

        /** Maps a refusal of the playback endpoint. */
        fun fromServer(httpStatus: Int, serverCode: String?, message: String?): WolvyError {
            val text = message?.takeIf { it.isNotBlank() } ?: "The server refused the video (HTTP $httpStatus)."
            val code = when {
                serverCode in NETWORKISH || httpStatus >= 500 -> Code.NETWORK_ERROR
                serverCode != null -> Code.PLAYBACK_ERROR
                else -> Code.FAILED
            }
            return WolvyError(code, text, serverCode ?: "http_$httpStatus")
        }
    }
}
