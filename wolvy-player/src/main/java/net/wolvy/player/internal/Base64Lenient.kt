package net.wolvy.player.internal

/**
 * Decodes standard or URL-safe base64, with or without padding. java.util.Base64 needs
 * Android 8 (API 26) and this SDK supports Android 7 (API 24), so it is done by hand.
 */
internal object Base64Lenient {
    private val VALUES = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62; t['_'.code] = 63
    }

    /** Null when [input] is not base64. */
    fun decode(input: String): ByteArray? {
        val s = input.trim().trimEnd('=')
        if (s.isEmpty() || s.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = if (c.code < 128) VALUES[c.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
