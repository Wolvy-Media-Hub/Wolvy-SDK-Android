package net.wolvy.player.internal

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

    private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * Base64url without padding (RFC 4648 §5). Written out because java.util.Base64 needs API 26
     * (the SDK's minimum is 24) and android.util.Base64 does nothing in JVM unit tests.
     */
    fun b64url(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1
            out.append(B64URL[b0 shr 2])
            out.append(B64URL[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            if (b1 >= 0) out.append(B64URL[((b1 and 0x0f) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            if (b2 >= 0) out.append(B64URL[b2 and 0x3f])
            i += 3
        }
        return out.toString()
    }
}
