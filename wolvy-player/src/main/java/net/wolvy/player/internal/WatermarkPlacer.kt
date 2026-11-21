package net.wolvy.player.internal

import kotlin.random.Random

/**
 * Where the watermark goes inside the video, ported from Wolvy Player (PlayerScreen.tsx
 * wmPosition). Moving: a random spot, 2 to 78 % from the left and 4 to 85 % from the top, a new
 * one every [MOVE_EVERY_MS]. Static: a corner from the account's setting, 16 dp from the edges.
 */
internal object WatermarkPlacer {
    const val MOVE_EVERY_MS: Long = 5000
    const val MARGIN_DP: Int = 16

    sealed class Spot {
        /** Fractions of the video's width and height for the text's top-left corner. */
        data class Moving(val left: Double, val top: Double) : Spot()
        data class Corner(val top: Boolean, val left: Boolean) : Spot()
    }

    fun place(mode: String, position: String, random: Random = Random.Default): Spot {
        if (mode == "moving") {
            return Spot.Moving(
                left = (2 + random.nextInt(0, 77)) / 100.0,   // 2..78 %
                top = (4 + random.nextInt(0, 82)) / 100.0,    // 4..85 %
            )
        }
        val p = position.ifBlank { "bottom-right" }
        return Spot.Corner(top = p.startsWith("top"), left = p.contains("left"))
    }
}
