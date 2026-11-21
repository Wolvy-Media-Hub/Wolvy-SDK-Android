package net.wolvy.player.internal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager

/**
 * FLAG_SECURE on the window that shows the video, confirmed before playback, as in Wolvy Player
 * (WolvySecurityModule.enableSecureWindow). The flag belongs to the whole window: while the
 * player is on screen, the app's whole screen is protected from capture.
 */
internal class ScreenGuard(private val context: Context) {
    private var addedByUs = false

    /** Sets the flag; true when it is set (by us or already). */
    fun enable(): Boolean {
        val window = activity(context)?.window ?: return false
        return try {
            val had = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
            if (!had) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                addedByUs = true
            }
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        } catch (e: Exception) {
            false
        }
    }

    /** Clears the flag only if this guard set it. */
    fun disable() {
        if (!addedByUs) return
        addedByUs = false
        try { activity(context)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } catch (_: Exception) {}
    }

    private fun activity(c: Context?): Activity? {
        var cur = c
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return null
    }
}
