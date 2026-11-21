package net.wolvy.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import net.wolvy.player.internal.ScreenGuard
import net.wolvy.player.internal.WatermarkPlacer
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a [WolvyPlayer]: the video on a secure surface, the viewer watermark, captions, and
 * (unless [showControls] is false) controls in the account's colour with quality, captions and
 * speed in a menu.
 *
 * It sets FLAG_SECURE on its window while attached and the player plays only once that is
 * confirmed: screenshots and screen recordings of the whole screen are black meanwhile.
 * The fullscreen button reports [WolvyPlayerListener.onFullscreenChange]; the app resizes the view.
 */
@OptIn(UnstableApi::class)
public class WolvyPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private companion object {
        const val DEFAULT_ACCENT = 0xFF7C3AED.toInt()
    }

    private val main = Handler(Looper.getMainLooper())
    private val guard = ScreenGuard(context)
    private val exoView = PlayerView(context)
    private val videoBox = FrameLayout(context)
    private val watermark = TextView(context)
    private val caption = TextView(context)
    private val message = TextView(context)
    private val settings = ImageButton(context)
    private var videoAspect = 0f
    private var fullscreen = false
    private var protectedWindow = false

    /** The player to show. Setting another one detaches the previous. */
    public var player: WolvyPlayer? = null
        set(value) {
            if (field === value) return
            field?.let { detach(it) }
            field = value
            value?.let { attach(it) }
        }

    /** False for a bare player: no controls, no menu (draw your own with the player's methods). */
    public var showControls: Boolean = true
        set(value) {
            field = value
            exoView.useController = value
            if (!value) settings.visibility = GONE
        }

    init {
        setBackgroundColor(Color.BLACK)
        // Before the surface exists: a secure SurfaceView cannot be captured or read into a texture.
        (exoView.videoSurfaceView as? SurfaceView)?.setSecure(true)
        exoView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        exoView.setShowSubtitleButton(false)
        exoView.setShowNextButton(false)
        exoView.setShowPreviousButton(false)
        exoView.setShowShuffleButton(false)
        exoView.setShowVrButton(false)
        exoView.setFullscreenButtonClickListener { full ->
            fullscreen = full
            player?.emitFullscreen(full)
        }
        exoView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            settings.visibility = if (showControls && player?.videoInfo != null) visibility else GONE
        })
        addView(exoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        videoBox.clipChildren = true
        videoBox.clipToPadding = true
        videoBox.isClickable = false
        watermark.setTextColor(Color.WHITE)
        watermark.setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.BLACK)
        watermark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        watermark.typeface = Typeface.DEFAULT_BOLD
        watermark.maxLines = 1
        watermark.visibility = GONE
        videoBox.addView(watermark, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        caption.setTextColor(Color.WHITE)
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        caption.setBackgroundColor(Color.argb(160, 0, 0, 0))
        caption.setPadding(dp(8), dp(4), dp(8), dp(4))
        caption.gravity = Gravity.CENTER
        caption.visibility = GONE
        videoBox.addView(caption, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(56); leftMargin = dp(16); rightMargin = dp(16) })
        addView(videoBox, LayoutParams(0, 0))

        settings.setImageResource(R.drawable.wolvy_ic_settings)
        settings.setBackgroundColor(Color.TRANSPARENT)
        settings.contentDescription = "Quality, captions and speed"
        settings.visibility = GONE
        settings.setOnClickListener { openMenu() }
        addView(settings, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))

        message.setTextColor(Color.WHITE)
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        message.gravity = Gravity.CENTER
        message.setPadding(dp(24), dp(16), dp(24), dp(16))
        message.visibility = GONE
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    // ---- attaching ---------------------------------------------------------------------------

    private val events = object : WolvyPlayerListener {
        override fun onReady(state: State) {
            message.visibility = GONE
            applyLook()
        }
        override fun onCaptionCue(text: String?) {
            caption.text = text ?: ""
            caption.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
        }
        override fun onError(error: WolvyError) {
            message.text = when (error.code) {
                WolvyError.Code.DEVICE_NOT_SUPPORTED -> error.message
                else -> "${error.message}\nTap to try again."
            }
            message.visibility = VISIBLE
            message.setOnClickListener(if (error.code == WolvyError.Code.DEVICE_NOT_SUPPORTED) null else View.OnClickListener {
                message.visibility = GONE
                try { player?.retry() } catch (_: WolvyError) {}
            })
        }
    }

    private val videoSizes = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoAspect = if (videoSize.width > 0 && videoSize.height > 0) {
                videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            } else 0f
            layoutVideoBox()
        }
    }

    private fun attach(p: WolvyPlayer) {
        exoView.player = p.exo
        p.addListener(events)
        p.exo.addListener(videoSizes)
        if (isAttachedToWindow) protect(p)
        p.lastError?.let { events.onError(it) }
        if (p.videoInfo != null) applyLook()
    }

    private fun detach(p: WolvyPlayer) {
        p.setProtection(this, false)
        p.removeListener(events)
        p.exo.removeListener(videoSizes)
        exoView.player = null
        main.removeCallbacks(moveWatermark)
        watermark.visibility = GONE
        caption.visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        player?.let { protect(it) }
    }

    override fun onDetachedFromWindow() {
        player?.setProtection(this, false)
        guard.disable()
        protectedWindow = false
        main.removeCallbacks(moveWatermark)
        super.onDetachedFromWindow()
    }

    private fun protect(p: WolvyPlayer) {
        protectedWindow = guard.enable()
        if (protectedWindow) {
            p.setProtection(this, true)
        } else {
            p.failFromView(WolvyError(WolvyError.Code.PLAYBACK_ERROR,
                "Screen protection could not be turned on, so the video does not play.",
                "screen_protection_unavailable"))
        }
    }

    // ---- look: colour, chapters, watermark ----------------------------------------------

    private fun applyLook() {
        val info = player?.videoInfo ?: return
        val accent = info.playerColor ?: DEFAULT_ACCENT
        exoView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(accent)
            setScrubberColor(accent)
            setAdMarkerColor(Color.argb(200, 255, 255, 255))
            setPlayedAdMarkerColor(Color.argb(200, 255, 255, 255))
        }
        // Chapter starts as marks on the seek bar (not the one at 0).
        val starts = info.chapters.map { (it.start * 1000).toLong() }.filter { it > 0 }
        exoView.setExtraAdGroupMarkers(starts.toLongArray(), BooleanArray(starts.size))

        val wm = info.watermark
        val viewer = info.viewerId
        main.removeCallbacks(moveWatermark)
        if (wm.enabled && !viewer.isNullOrEmpty()) {
            watermark.text = viewer
            watermark.alpha = wm.opacity / 100f
            watermark.visibility = VISIBLE
            placeWatermark()
            if (wm.mode == "moving") main.postDelayed(moveWatermark, WatermarkPlacer.MOVE_EVERY_MS)
        } else {
            watermark.visibility = GONE
        }
        settings.visibility = if (showControls && exoView.isControllerFullyVisible) VISIBLE else GONE
    }

    private val moveWatermark = object : Runnable {
        override fun run() {
            placeWatermark()
            main.postDelayed(this, WatermarkPlacer.MOVE_EVERY_MS)
        }
    }

    private fun placeWatermark() {
        val wm = player?.videoInfo?.watermark ?: return
        val lp = watermark.layoutParams as LayoutParams
        when (val spot = WatermarkPlacer.place(wm.mode, wm.position)) {
            is WatermarkPlacer.Spot.Corner -> {
                lp.gravity = (if (spot.top) Gravity.TOP else Gravity.BOTTOM) or (if (spot.left) Gravity.START else Gravity.END)
                val m = dp(WatermarkPlacer.MARGIN_DP)
                lp.setMargins(m, m, m, m)
                watermark.translationX = 0f
                watermark.translationY = 0f
            }
            is WatermarkPlacer.Spot.Moving -> {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.setMargins(0, 0, 0, 0)
                val boxW = videoBox.width
                val boxH = videoBox.height
                watermark.post {
                    watermark.translationX = min((spot.left * boxW).toFloat(), max(0f, (boxW - watermark.width).toFloat()))
                    watermark.translationY = min((spot.top * boxH).toFloat(), max(0f, (boxH - watermark.height).toFloat()))
                }
            }
        }
        watermark.layoutParams = lp
    }

    // ---- the video's rectangle: watermark and captions stay inside it -------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { layoutVideoBox() }
    }

    private fun layoutVideoBox() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        var boxW = w
        var boxH = h
        if (videoAspect > 0f) {
            if (w / h.toFloat() > videoAspect) boxW = (h * videoAspect).toInt() else boxH = (w / videoAspect).toInt()
        }
        val lp = videoBox.layoutParams as LayoutParams
        lp.width = boxW
        lp.height = boxH
        lp.leftMargin = (w - boxW) / 2
        lp.topMargin = (h - boxH) / 2
        lp.gravity = Gravity.TOP or Gravity.START
        videoBox.layoutParams = lp
        if (watermark.visibility == VISIBLE) videoBox.post { placeWatermark() }
    }

    // ---- the menu: quality, captions, speed -----------------------------------------------

    private fun openMenu() {
        val p = player ?: return
        val menu = PopupMenu(context, settings)
        val q = p.getQualities()
        if (q.available.isNotEmpty()) {
            val sub = menu.menu.addSubMenu(0, 0, 0, "Quality")
            sub.add(1, 0, 0, "Auto").isChecked = q.selected == null
            q.available.forEachIndexed { i, h -> sub.add(1, 1 + i, 1 + i, "${h}p").isChecked = q.selected == h }
            sub.setGroupCheckable(1, true, true)
        }
        val c = p.getCaptions()
        if (c.available.isNotEmpty()) {
            val sub = menu.menu.addSubMenu(0, 1, 1, "Captions")
            sub.add(2, 0, 0, "Off").isChecked = c.active == null
            c.available.forEachIndexed { i, t -> sub.add(2, 1 + i, 1 + i, t.label).isChecked = c.active == t.language }
            sub.setGroupCheckable(2, true, true)
        }
        val speed = menu.menu.addSubMenu(0, 2, 2, "Speed")
        PLAYBACK_RATES.forEachIndexed { i, r ->
            speed.add(3, i, i, if (r == 1f) "Normal" else "${r}×").isChecked = p.playbackRate == r
        }
        speed.setGroupCheckable(3, true, true)
        menu.setOnMenuItemClickListener { item ->
            try {
                when (item.groupId) {
                    1 -> p.setQuality(if (item.itemId == 0) null else q.available[item.itemId - 1])
                    2 -> p.setCaptions(if (item.itemId == 0) null else c.available[item.itemId - 1].language)
                    3 -> p.setPlaybackRate(PLAYBACK_RATES[item.itemId])
                }
            } catch (_: WolvyError) {
            }
            true
        }
        menu.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
