package net.wolvy.player.sample

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import net.wolvy.player.Progress
import net.wolvy.player.Qualities
import net.wolvy.player.State
import net.wolvy.player.WolvyError
import net.wolvy.player.WolvyPlayer
import net.wolvy.player.WolvyPlayerListener
import net.wolvy.player.WolvyPlayerView
import net.wolvy.player.WolvyVideo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Wolvy Android SDK's sample: paste the `embed_url` that your server gets from
 * POST /v1/videos/{id}/playback-tokens and press Play. In a real app the three values come from
 * your own server; the viewer token is never made in the app.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var player: WolvyPlayer
    private lateinit var playerView: WolvyPlayerView
    private lateinit var form: LinearLayout
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = WolvyPlayer(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(this).apply {
            hint = "embed_url from your server (https://embed.wolvy.stream/…?vt=…)"
            isSingleLine = true
        }
        val play = Button(this).apply { text = "Play" }
        play.setOnClickListener {
            try {
                player.load(WolvyVideo.fromEmbedUrl(input.text.toString()))
                line("load ${input.text.toString().substringBefore('?')}")
            } catch (e: IllegalArgumentException) {
                line("not an embed URL with a viewer token: ${e.message}")
            }
        }
        form.addView(input)
        form.addView(play)
        root.addView(form)

        playerView = WolvyPlayerView(this)
        playerView.player = player
        root.addView(playerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        log = TextView(this).apply { textSize = 11f; setPadding(16, 8, 16, 8); setTextIsSelectable(true) }
        logScroll = ScrollView(this).apply { addView(log) }
        root.addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.6f))
        setContentView(root)

        line("Widevine ${player.widevineLevel}, app id $packageName (must be on the account's Allowed apps)")
        player.addListener(object : WolvyPlayerListener {
            override fun onReady(state: State) =
                line("ready, ${"%.0f".format(state.duration)} s, ${if (state.attested) "app proven (app protection)" else "app not proven"}")
            override fun onPlay(currentTime: Double) = line("play at ${"%.1f".format(currentTime)}")
            override fun onPause(currentTime: Double) = line("pause at ${"%.1f".format(currentTime)}")
            override fun onEnded() = line("ended")
            override fun onSeeked(currentTime: Double) = line("seeked to ${"%.1f".format(currentTime)}")
            override fun onProgress(progress: Progress) = line("progress ${progress.percent} % (${progress.seconds} s)")
            override fun onQualityChange(qualities: Qualities) =
                line("quality ${qualities.available}, picked ${qualities.selected ?: "auto"}, playing ${qualities.playing}")
            override fun onCaptionsChange(active: String?) = line("captions ${active ?: "off"}")
            override fun onRateChange(playbackRate: Float) = line("speed $playbackRate")
            override fun onError(error: WolvyError) = line("error ${error.code.value} (${error.reason}): ${error.message}")
            override fun onFullscreenChange(fullscreen: Boolean) = setFullscreen(fullscreen)
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (form.visibility == View.GONE) setFullscreen(false) else finish()
            }
        })
    }

    /** The app owns the layout: the view only reports the button. */
    @Suppress("DEPRECATION")
    private fun setFullscreen(on: Boolean) {
        form.visibility = if (on) View.GONE else View.VISIBLE
        logScroll.visibility = if (on) View.GONE else View.VISIBLE
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else 0
    }

    private fun line(text: String) {
        log.append("${clock.format(Date())}  $text\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onStop() {
        super.onStop()
        try { player.pause() } catch (_: WolvyError) {}
    }

    override fun onDestroy() {
        playerView.player = null
        player.release()
        super.onDestroy()
    }
}
