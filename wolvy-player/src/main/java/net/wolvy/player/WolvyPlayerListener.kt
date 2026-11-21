package net.wolvy.player

/**
 * Player events, with the web Player API's names and data. Override only what you need.
 * Called on the main thread.
 */
public interface WolvyPlayerListener {
    /** The video is loaded and accepts every command. Sent once per [WolvyPlayer.load]. */
    public fun onReady(state: State) {}
    public fun onPlay(currentTime: Double) {}
    public fun onPause(currentTime: Double) {}
    public fun onEnded() {}
    /** After a jump, whoever asked for it. */
    public fun onSeeked(currentTime: Double) {}
    /** About 4 times a second while playing. */
    public fun onTimeUpdate(currentTime: Double, duration: Double) {}
    /** As the share played changes, at most once a second, and once more at the end. */
    public fun onProgress(progress: Progress) {}
    public fun onVolumeChange(volume: Float, muted: Boolean) {}
    public fun onRateChange(playbackRate: Float) {}
    public fun onQualityChange(qualities: Qualities) {}
    /** A language code, or null when captions are off. */
    public fun onCaptionsChange(active: String?) {}
    /** The fullscreen button of [WolvyPlayerView] was pressed; the app resizes the view. */
    public fun onFullscreenChange(fullscreen: Boolean) {}
    /** The video could not be loaded or played. After this, control methods throw the same error. */
    public fun onError(error: WolvyError) {}
    /** Android only: the caption text to show now, or null. For apps that draw their own captions. */
    public fun onCaptionCue(text: String?) {}
}
