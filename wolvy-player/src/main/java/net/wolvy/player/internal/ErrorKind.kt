package net.wolvy.player.internal

/**
 * Sorts a Media3 error code (PlaybackException.errorCode) the way the recovery needs it.
 * DRM and decoder errors (6000s, 4000s) are what a missing key looks like: on a software-DRM
 * device the HD key is left out of the licence and decoding the HD rendition fails. Network and
 * HTTP errors (2000s) include a CDN token that ran out (HTTP 403).
 */
internal object ErrorKind {
    fun isDrm(errorCode: Int, causeText: String): Boolean =
        errorCode in 6000..6999 || errorCode in 4000..4999 ||
            Regex("""drm|crypto|no.?key|keystatus|MediaCodec""", RegexOption.IGNORE_CASE).containsMatchIn(causeText)

    fun isNetwork(errorCode: Int): Boolean = errorCode in 2000..2999
}
