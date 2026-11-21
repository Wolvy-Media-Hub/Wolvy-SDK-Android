package net.wolvy.player.internal

/**
 * Which heights a device is offered, and what the track selector may pick.
 *
 * Ported from Wolvy Player (PlayerScreen.tsx, onLoad and onVideoError). Software-DRM devices
 * (Widevine L2, L3) never receive the HD keys: the licence server leaves them out. So they are
 * offered SD heights only, capped at [SD_MAX_HEIGHT] from the very start, rather than failing
 * when the player picks HD. The licence server decides; this only avoids asking for keys the
 * device will not get. A step-down after a DRM error does the same for any device.
 */
internal class QualityPolicy(private val softwareDrm: Boolean) {
    companion object {
        /** The tallest rendition under the SD key (the licence policy's software tier, as on the web). */
        const val SD_MAX_HEIGHT: Int = 480
    }

    /** Heights in the video, highest first. */
    var allHeights: List<Int> = emptyList()
        private set

    /** Null = automatic. */
    var selected: Int? = null
        private set

    var steppedDown: Boolean = false
        private set

    private val sdOnly: Boolean get() = softwareDrm || steppedDown

    /** Heights on offer, highest first. */
    val available: List<Int>
        get() = if (sdOnly) allHeights.filter { it <= SD_MAX_HEIGHT } else allHeights

    /** Sets the heights found in the manifest. False when a software-DRM device has nothing it may play. */
    fun onTracks(heights: Collection<Int>): Boolean {
        allHeights = heights.filter { it > 0 }.distinct().sortedDescending()
        if (selected != null && selected !in available) selected = null
        return !(sdOnly && allHeights.isNotEmpty() && available.isEmpty())
    }

    /** Null = automatic. False when [height] is not on offer. */
    fun select(height: Int?): Boolean {
        if (height != null && height !in available) return false
        selected = height
        return true
    }

    /** Cap to SD after a DRM error; a pinned HD height goes back to automatic. */
    fun stepDown() {
        steppedDown = true
        val s = selected
        if (s != null && s > SD_MAX_HEIGHT) selected = null
    }

    /** The video-size constraints for the track selector: max height, min height. */
    fun constraints(): Pair<Int, Int> {
        selected?.let { return it to it }
        return if (sdOnly) (available.firstOrNull() ?: SD_MAX_HEIGHT) to 0 else Int.MAX_VALUE to 0
    }
}
