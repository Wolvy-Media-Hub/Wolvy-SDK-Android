package net.wolvy.player

/**
 * Gets a fresh viewer token from **your server** when the player needs one. With it, tokens can
 * be short-lived (minutes): a leaked token is then worth little.
 *
 * The player calls it, on a background thread (a blocking network call is fine):
 * - before asking Wolvy, when the token it has expires within a minute (the expiry is read from
 *   the token; only Wolvy's server verifies it);
 * - once more if Wolvy refuses the token (`viewer_token_required`).
 *
 * Sign the token on your server (`POST /v1/videos/{id}/playback-tokens`, or the server SDKs'
 * `signViewerToken`). Never sign it in the app: that needs the account's signing secret.
 */
public fun interface ViewerTokenProvider {
    /** A fresh token for [video]'s viewer, or null when there is none (the player then reports the error). */
    @Throws(Exception::class)
    public fun fetchViewerToken(video: WolvyVideo): String?
}
