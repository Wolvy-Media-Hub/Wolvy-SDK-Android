package net.wolvy.player.internal

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import java.util.WeakHashMap

/**
 * FLAG_SECURE on the window that shows the video, confirmed before playback, as in Wolvy Player
 * (WolvySecurityModule.enableSecureWindow). The flag belongs to the whole window: while a player
 * is on screen, the app's whole screen is protected from capture.
 *
 * Several views can share one window (two players, or a new view that appears before the old one
 * is removed, as Flutter and React Native do when a layout changes). The flag is counted per
 * window and cleared only when the last view leaves, and only if the SDK set it.
 */
internal class ScreenGuard(private val context: Context) {
    private var held: Window? = null

    /** Holds the flag on this view's window; true when it is set. Calling it again is harmless. */
    fun enable(): Boolean {
        held?.let { return isSet(it) }
        val window = activity(context)?.window ?: return false
        return try {
            WINDOWS.acquire(window)
            held = window
            isSet(window)
        } catch (e: Exception) {
            false
        }
    }

    /** Lets go of this view's hold; the flag goes when no view holds it. */
    fun disable() {
        val window = held ?: return
        held = null
        try { WINDOWS.release(window) } catch (_: Exception) {}
    }

    private fun activity(c: Context?): Activity? {
        var cur = c
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return null
    }

    private companion object {
        fun isSet(w: Window) = (w.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

        val WINDOWS = SecureFlagCounter<Window>(
            isSet = ::isSet,
            set = { it.addFlags(WindowManager.LayoutParams.FLAG_SECURE) },
            clear = { it.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) },
            holds = WeakHashMap(),
        )
    }
}

/**
 * Counts the holders of a flag per key (a window). The first holder sets the flag unless it was
 * already set; the last one to let go clears it, only if it was set here. Main thread only.
 */
internal class SecureFlagCounter<K>(
    private val isSet: (K) -> Boolean,
    private val set: (K) -> Unit,
    private val clear: (K) -> Unit,
    private val holds: MutableMap<K, Hold> = HashMap(),
) {
    class Hold(var count: Int, val setHere: Boolean)

    fun acquire(key: K) {
        val h = holds[key]
        if (h != null) { h.count++; return }
        val had = isSet(key)
        if (!had) set(key)
        holds[key] = Hold(1, setHere = !had)
    }

    fun release(key: K) {
        val h = holds[key] ?: return
        h.count--
        if (h.count > 0) return
        holds.remove(key)
        if (h.setHere) clear(key)
    }

    fun holders(key: K): Int = holds[key]?.count ?: 0
}
