package net.wolvy.player.internal

/**
 * Removes tokens from text before it leaves the SDK in an error: viewer and licence tokens
 * (JWT-like `eyJ….…`), token query parameters (`token=`, `exp=`, `vt=`…) and token headers.
 * Media3's exceptions can carry the CDN address with its token; an app that sends errors to a
 * crash reporter must not send that along.
 */
internal object Scrub {
    private val QUERY = Regex("""([?&](?:token|exp|vt|key|sig|signature)=)[^&\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val JWT = Regex("""eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}(?:\.[A-Za-z0-9_-]+)?""")
    private val HEADER = Regex("""(X-CDN-Token|X-CDN-Exp|Authorization)(\s*[:=]\s*)[^,\s}\]]+""", RegexOption.IGNORE_CASE)

    fun text(s: String): String =
        HEADER.replace(QUERY.replace(JWT.replace(s, "[token]"), "$1[removed]"), "$1$2[removed]")

    /** The class and the scrubbed message of [t] and its causes, outermost first. */
    fun summary(t: Throwable): String =
        generateSequence(t) { it.cause }.take(6)
            .joinToString(" <- ") { "${it.javaClass.simpleName}: ${text(it.message ?: "")}".trimEnd(' ', ':') }
}

/**
 * What a [net.wolvy.player.WolvyError] carries as its cause instead of the original exception:
 * the same story in words, without the original objects (which can hold request addresses and
 * headers with tokens).
 */
internal class ScrubbedCause(summary: String) : Exception(summary) {
    override fun fillInStackTrace(): Throwable = this
}
