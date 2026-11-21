package net.wolvy.player.internal

/** One caption cue, in seconds. */
internal data class Cue(val start: Double, val end: Double, val text: String)

/**
 * Minimal WebVTT parser, ported from Wolvy Player (PlayerScreen.tsx parseVTT): timed cues only;
 * the WEBVTT header, NOTE blocks and cue ids are skipped and inline tags (<v>, <b>, <i>…) are
 * removed. Hours are optional and a comma decimal separator is accepted. The SDK draws captions
 * itself because a sideloaded VTT track with DASH proved unreliable on Android.
 */
internal object VttParser {
    private val TIMING = Regex(
        """(?:(\d{1,3}):)?(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(?:(\d{1,3}):)?(\d{2}):(\d{2})[.,](\d{3})"""
    )
    private val TAG = Regex("<[^>]+>")

    fun parse(vtt: String): List<Cue> {
        val lines = vtt.replace("\r", "").split('\n')
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = TIMING.find(lines[i])
            if (m == null) { i++; continue }
            val g = m.groupValues
            val start = seconds(g[1], g[2], g[3], g[4])
            val end = seconds(g[5], g[6], g[7], g[8])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            val text = TAG.replace(body.toString(), "").trim()
            if (text.isNotEmpty()) cues.add(Cue(start, end, text))
        }
        return cues
    }

    /** The cue showing at [t] seconds, or null. */
    fun at(cues: List<Cue>, t: Double): Cue? = cues.firstOrNull { t >= it.start && t < it.end }

    private fun seconds(h: String, m: String, s: String, ms: String): Double =
        (h.toIntOrNull() ?: 0) * 3600.0 + m.toInt() * 60 + s.toInt() + ms.toInt() / 1000.0
}
