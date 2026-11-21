package net.wolvy.player.internal

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The strings and signatures of app protection, exactly as Wolvy's server makes them
 * (Wolvy-Backend's app_protection.php and sdk_app_key.php). Pure functions; the unit tests check
 * them against values the server's PHP gave.
 */
internal object AppSigning {
    /** What the device proves: ties the proof to the server's challenge, the account and the app. */
    fun bound(challenge: String, accountId: Long, platform: String, appId: String): String =
        "wolvy-attest|1|$challenge|$accountId|$platform|$appId"

    /** The Classic Play Integrity nonce: base64url, unpadded, of SHA-256 of [bound]. */
    fun playIntegrityNonce(bound: String): String =
        b64url(sha256(bound.toByteArray(Charsets.UTF_8)))

    /** app_render_video's X-Wolvy-App-Signature. The key is used as text, as the server does. */
    fun renderSignature(key: String, videoId: String, accountId: Long, timestamp: Long): String =
        hmacHex(key, "$videoId:$accountId:$timestamp")

    /** The licence servers' X-Wolvy-Licence-Signature: over the SHA-256 (hex) of the licence token. */
    fun licenceSignature(key: String, licenceToken: String, timestamp: Long): String =
        hmacHex(key, hex(sha256(licenceToken.toByteArray(Charsets.UTF_8))) + ":" + timestamp)

    fun hmacHex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Base64url without padding. java.util.Base64 is used where it exists (API 26+, and the JVM of
     * the unit tests); android.util.Base64 below that.
     */
    fun b64url(bytes: ByteArray): String =
        try {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } catch (e: NoClassDefFoundError) {
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }
}
