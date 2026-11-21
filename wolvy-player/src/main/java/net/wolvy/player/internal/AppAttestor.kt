package net.wolvy.player.internal

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityServiceException
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.model.IntegrityErrorCode
import net.wolvy.player.WolvyError
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * App protection (SDK plan phase 5): proves this app to Wolvy with the customer's own Play
 * Integrity, and keeps the app key Wolvy gives back. With the key, app_render_video and the
 * licence requests are signed, and Wolvy treats the app like Wolvy Player.
 *
 * 1. `POST {api}/app_challenge` says how the app proves itself. `method: null` means the account
 *    has not set up protection for this app: the app plays unproven (browser rules).
 * 2. A Classic Play Integrity token, with no Cloud project number (so it runs under the project
 *    linked to the app in the customer's Play Console) and the nonce of [AppSigning.bound].
 * 3. `POST {api}/app_attest` checks it and answers the app key (6 hours).
 *
 * Every failure falls back to unproven, and is not tried again for a while, so playback never
 * waits on it twice. A refusal from Wolvy (rooted phone, a copy of the app) is kept in
 * [lastRefusal] so the error shown when the account requires proven apps says why.
 * Only called on the player's background thread.
 */
internal class AppAttestor(
    private val appId: String,
    private val sdk: String,
    private val apiBase: String,
    private val tokens: IntegrityTokens,
    private val post: (String, Map<String, String>, Map<String, String>) -> WolvyHttp.Answer = WolvyHttp::postForm,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    class AppKey(val accountId: Long, val keyId: String, val key: String, val expiresAt: Long)

    /** Gets a Play Integrity token. Throws [IntegrityFailure] when it cannot. */
    fun interface IntegrityTokens {
        fun classicToken(nonce: String): String
    }

    class IntegrityFailure(val code: Int, val retryable: Boolean, message: String?) : Exception(message)

    private companion object {
        const val TAG = "WolvyPlayer"
        /** Renew the key this long before it ends. */
        const val RENEW_MARGIN = 60L
        /** How long to play unproven before trying again, by cause. */
        const val AGAIN_NOT_SET_UP = 600L
        const val AGAIN_REFUSED = 600L
        const val AGAIN_FAILED = 300L
        const val AGAIN_NETWORK = 30L
        /** Play Integrity: attempts for a transient error, and the first wait (doubled each time). */
        const val ATTEMPTS = 3
        const val FIRST_WAIT_MS = 1_000L
    }

    @Volatile private var key: AppKey? = null
    private var waitAccount = 0L
    private var waitUntil = 0L

    /** Wolvy's last refusal of this app's proof (device_compromised, app_not_genuine, …), if any. */
    @Volatile var lastRefusal: WolvyError? = null
        private set

    /** A valid app key for [accountId], or null to play unproven. */
    fun keyFor(accountId: Long): AppKey? {
        val now = clock()
        key?.let { if (it.accountId == accountId && it.expiresAt - now > RENEW_MARGIN) return it }
        if (waitAccount == accountId && now < waitUntil) return null
        return try {
            attest(accountId).also { key = it; lastRefusal = null }
        } catch (e: Unproven) {
            Log.i(TAG, "app protection: playing unproven (${e.reason})")
            key = null
            waitAccount = accountId
            waitUntil = now + e.againAfter
            null
        }
    }

    /** Wolvy refused the key (expired or unknown): prove the app again on the next request. */
    fun invalidate() {
        key = null
        waitUntil = 0
    }

    private class Unproven(val reason: String, val againAfter: Long) : Exception(reason)

    private fun headers() = mapOf("X-Wolvy-Platform" to "android", "X-Wolvy-App-Id" to appId, "X-Wolvy-SDK" to sdk)

    private fun call(endpoint: String, form: Map<String, String>): WolvyHttp.Answer =
        try {
            post("${apiBase.trimEnd('/')}/$endpoint", headers(), form)
        } catch (e: WolvyError) {
            throw Unproven("$endpoint: ${e.reason}", AGAIN_NETWORK)
        }

    private fun attest(accountId: Long): AppKey {
        val account = accountId.toString()
        val ch = call("app_challenge", mapOf("user_id" to account))
        if (ch.json.optString("status") != "success") {
            throw Unproven("app_challenge: ${ch.json.optString("code").ifEmpty { "http_${ch.status}" }}",
                if (ch.status == 429 || ch.status >= 500) AGAIN_NETWORK else AGAIN_NOT_SET_UP)
        }
        val method = if (ch.json.isNull("method")) "" else ch.json.optString("method")
        if (method != "play_integrity") throw Unproven("not set up for this app", AGAIN_NOT_SET_UP)
        val challenge = ch.json.optString("challenge")
        if (!Regex("^[0-9a-f]{64}$").matches(challenge)) throw Unproven("bad challenge", AGAIN_FAILED)

        val nonce = AppSigning.playIntegrityNonce(AppSigning.bound(challenge, accountId, "android", appId))
        val token = integrityToken(nonce)

        val at = call("app_attest", mapOf("user_id" to account, "challenge" to challenge, "token" to token))
        val j = at.json
        if (j.optString("status") == "success") {
            val keyId = j.optString("key_id")
            val keyValue = j.optString("key")
            val expires = j.optLong("expires_at", 0)
            if (keyId.isEmpty() || keyValue.isEmpty() || expires <= 0) throw Unproven("bad app key answer", AGAIN_FAILED)
            return AppKey(accountId, keyId, keyValue, expires)
        }
        val code = j.optString("code").ifEmpty { "http_${at.status}" }
        if (code == "device_compromised" || code == "app_not_genuine" || code == "attestation_failed") {
            lastRefusal = WolvyError(
                if (code == "device_compromised") WolvyError.Code.DEVICE_NOT_SUPPORTED else WolvyError.Code.PLAYBACK_ERROR,
                j.optString("message").ifEmpty { "This app couldn't verify itself." }, code,
            )
        }
        throw Unproven("app_attest: $code", if (code == "attestation_failed") AGAIN_NETWORK else AGAIN_REFUSED)
    }

    /** Google asks for retries on transient errors: up to [ATTEMPTS], waiting 1 s, then 2 s. */
    private fun integrityToken(nonce: String): String {
        var wait = FIRST_WAIT_MS
        for (attempt in 1..ATTEMPTS) {
            try {
                return tokens.classicToken(nonce)
            } catch (e: IntegrityFailure) {
                if (!e.retryable || attempt == ATTEMPTS) {
                    throw Unproven("Play Integrity ${e.code}", if (e.retryable) AGAIN_NETWORK else AGAIN_FAILED)
                }
                sleep(wait)
                wait *= 2
            }
        }
        throw Unproven("Play Integrity", AGAIN_FAILED)
    }
}

/** Classic Play Integrity tokens from Google Play services, with no Cloud project number. */
internal class PlayIntegrityTokens(context: Context) : AppAttestor.IntegrityTokens {
    private val app = context.applicationContext

    private companion object {
        val RETRYABLE = setOf(
            IntegrityErrorCode.NETWORK_ERROR,
            IntegrityErrorCode.TOO_MANY_REQUESTS,
            IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE,
            IntegrityErrorCode.CLIENT_TRANSIENT_ERROR,
            IntegrityErrorCode.INTERNAL_ERROR,
        )
    }

    override fun classicToken(nonce: String): String {
        val task = try {
            IntegrityManagerFactory.create(app).requestIntegrityToken(IntegrityTokenRequest.builder().setNonce(nonce).build())
        } catch (e: Exception) {
            // No Play Integrity on this device at all (no Google Play services).
            throw AppAttestor.IntegrityFailure(0, false, e.javaClass.simpleName)
        }
        return try {
            Tasks.await(task, 30, TimeUnit.SECONDS).token()
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is IntegrityServiceException) {
                throw AppAttestor.IntegrityFailure(cause.errorCode, cause.errorCode in RETRYABLE, cause.message)
            }
            throw AppAttestor.IntegrityFailure(0, false, cause?.javaClass?.simpleName)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw AppAttestor.IntegrityFailure(0, true, "timeout")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AppAttestor.IntegrityFailure(0, false, "interrupted")
        }
    }
}
