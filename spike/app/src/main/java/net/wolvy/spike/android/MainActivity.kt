package net.wolvy.spike.android

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import net.wolvy.spike.player.SpikePlayerView

/** Native host: the baseline. If hardware-protected video shows anywhere, it shows here. */
class MainActivity : AppCompatActivity() {
    private lateinit var player: SpikePlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screenshots and screen recordings of this window are black (what the SDK will do).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(this).apply {
            hint = "Paste the embed_url from the API (https://embed.wolvy.stream/…?vt=…)"
            isSingleLine = true
        }
        val play = Button(this).apply { text = "Play" }
        player = SpikePlayerView(this, "native")
        play.setOnClickListener { player.start(input.text.toString()) }
        root.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(play, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(player, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
