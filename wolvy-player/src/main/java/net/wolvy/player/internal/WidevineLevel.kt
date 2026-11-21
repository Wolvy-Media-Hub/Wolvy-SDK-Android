package net.wolvy.player.internal

import android.media.MediaDrm
import android.os.Build
import java.util.UUID

/** The device's Widevine security level, as Wolvy Player reads it (WolvySecurityModule). */
internal object WidevineLevel {
    private val WIDEVINE: UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")

    /** "L1", "L2", "L3" or "unknown". */
    fun read(): String {
        var drm: MediaDrm? = null
        return try {
            drm = MediaDrm(WIDEVINE)
            when (val level = drm.getPropertyString("securityLevel")) {
                "L1", "L2", "L3" -> level
                else -> "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm?.close()
                else @Suppress("DEPRECATION") drm?.release()
            } catch (_: Exception) {
            }
        }
    }
}
