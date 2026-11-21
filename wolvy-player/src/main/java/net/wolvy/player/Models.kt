package net.wolvy.player

/** What the `ready` event carries and [WolvyPlayer.getState] returns. Times in seconds. */
public data class State(
    val currentTime: Double,
    val duration: Double,
    val paused: Boolean,
    val muted: Boolean,
    val volume: Float,
    val playbackRate: Float,
    /** The app proved itself to Wolvy for this video (app protection): it plays like Wolvy Player. */
    val attested: Boolean = false,
)

/**
 * Qualities on offer. [selected] is the picked height, or null for automatic (the web API's
 * "auto"); [playing] is the height on screen, or null before the first frame.
 */
public data class Qualities(
    val available: List<Int>,
    val selected: Int?,
    val playing: Int?,
)

public data class CaptionTrack(val language: String, val label: String)

/** [active] is the language shown, or null when captions are off. */
public data class Captions(val available: List<CaptionTrack>, val active: String?)

/**
 * How much of the video was played, each second counted once: the same numbers as the web
 * player's `progress` event. [percent] has one decimal, 0 to 100.
 */
public data class Progress(val percent: Double, val seconds: Int, val duration: Double)

public data class Chapter(val start: Double, val end: Double, val title: String)

public data class Moment(val timestamp: Double, val label: String)

/** The account's watermark setting. [opacity] is a percent, 0 to 100. */
public data class WatermarkConfig(
    val enabled: Boolean,
    val mode: String,
    val position: String,
    val opacity: Int,
)

/** The speeds the player offers: the only values [WolvyPlayer.setPlaybackRate] accepts (as on the web). */
public val PLAYBACK_RATES: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
