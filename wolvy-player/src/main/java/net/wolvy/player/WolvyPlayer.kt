package net.wolvy.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import net.wolvy.player.internal.Cue
import net.wolvy.player.internal.ErrorKind
import net.wolvy.player.internal.PlaybackFetcher
import net.wolvy.player.internal.ProgressTracker
import net.wolvy.player.internal.QualityPolicy
import net.wolvy.player.internal.RecoveryPolicy
import net.wolvy.player.internal.Scrub
import net.wolvy.player.internal.ScrubbedCause
import net.wolvy.player.internal.VttParser
import net.wolvy.player.internal.WidevineLevel
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Plays Wolvy video: DASH with Widevine through Media3, the account's quality and protection
 * rules, and the web Player API's methods and events.
 *
 * Show it in a [WolvyPlayerView]. Nothing plays until that view has confirmed screen protection
 * (FLAG_SECURE) on its window. Create, call and release it on the main thread.
 *
 * ```
 * val player = WolvyPlayer(context)
 * playerView.player = player
 * player.addListener(object : WolvyPlayerListener {
 *     override fun onProgress(progress: Progress) { if (progress.percent >= 90) markLessonDone() }
 * })
 * player.load(WolvyVideo(videoId, accountId, viewerTokenFromYourServer))
 * ```
 *
 * @param source where playback data comes from; by default the app playback endpoint with this
 *   app's package name.
 */
@OptIn(UnstableApi::class)
public class WolvyPlayer @JvmOverloads constructor(
    context: Context,
    private val source: PlaybackSource = AppRenderVideoSource(context),
) {
    private companion object {
        const val TAG = "WolvyPlayer"
        const val TICK_MS = 250L
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wolvy-player-io").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<WolvyPlayerListener>()
    private val fetcher = PlaybackFetcher(source)
    private val userAgent = "wolvy-android/${BuildConfig.SDK_VERSION} (Android ${Build.VERSION.RELEASE}; ${app.packageName})"

    /** This device's Widevine level: "L1" (hardware), "L2", "L3" (software) or "unknown". */
    public val widevineLevel: String = WidevineLevel.read()
    private val softwareDrm = widevineLevel == "L2" || widevineLevel == "L3"

    private val mediaHttp = DefaultHttpDataSource.Factory().setUserAgent(userAgent)
    private val trackSelector = DefaultTrackSelector(app)
    internal val exo: ExoPlayer = ExoPlayer.Builder(app)
        .setTrackSelector(trackSelector)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(mediaHttp).setDrmSessionManagerProvider(
                DefaultDrmSessionManagerProvider().apply {
                    setDrmHttpDataSourceFactory(DefaultHttpDataSource.Factory().setUserAgent(userAgent))
                },
            ),
        )
        .build()

    private var quality = QualityPolicy(softwareDrm)
    private var recovery = RecoveryPolicy()
    private val progress = ProgressTracker()

    private var video: WolvyVideo? = null
    private var data: PlaybackData? = null
    private var tokenIssuedAt = 0L
    private var generation = 0
    private var readySent = false
    private var fatal: WolvyError? = null
    private var released = false
    private var wantPlay = false
    private var protectionConfirmed = false
    private var userVolume = 1f
    private var muted = false
    private var captionLanguage: String? = null
    private var cues: List<Cue> = emptyList()
    private var cueText: String? = null
    private var lastQualities: Qualities? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> if (!readySent && data != null) {
                        readySent = true
                        val s = getState()
                        emit { it.onReady(s) }
                    }
                    Player.STATE_ENDED -> {
                        emitProgress(force = true)
                        emit { it.onEnded() }
                    }
                    else -> Unit
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!readySent) return
                val t = currentTime
                if (playWhenReady) emit { it.onPlay(t) } else emit { it.onPause(t) }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    val t = newPosition.positionMs / 1000.0
                    emit { it.onSeeked(t) }
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                emit { it.onRateChange(playbackParameters.speed) }
            }

            override fun onTracksChanged(tracks: Tracks) = onTracks(tracks)

            override fun onVideoSizeChanged(videoSize: VideoSize) = emitQuality()

            override fun onPlayerError(error: PlaybackException) = onPlaybackError(error)
        })
    }

    // ---- loading -------------------------------------------------------------------------

    /**
     * Loads [video] (and stops whatever was playing). Playback starts when the video is ready
     * and the [WolvyPlayerView] showing it has confirmed screen protection, if [autoplay].
     */
    @JvmOverloads
    public fun load(video: WolvyVideo, autoplay: Boolean = true) {
        checkMain()
        if (released) throw destroyed()
        this.video = video
        data = null
        fatal = null
        readySent = false
        wantPlay = autoplay
        quality = QualityPolicy(softwareDrm)
        recovery = RecoveryPolicy()
        progress.reset()
        lastQualities = null
        clearCaptions()
        exo.stop()
        exo.clearMediaItems()
        val gen = ++generation
        fetch(gen, sessionId = null) { d ->
            apply(d)
            prepare(startMs = 0)
        }
    }

    /** After an error: try again from where playback stopped. */
    public fun retry() {
        checkMain()
        if (released) throw destroyed()
        val v = video ?: return
        if (data == null) { load(v, wantPlay); return }
        fatal = null
        recovery.reset()
        reload()
    }

    private fun fetch(gen: Int, sessionId: String?, then: (PlaybackData) -> Unit) {
        val v = video ?: return
        io.execute {
            val result: Result<PlaybackFetcher.Result> = try {
                Result.success(fetcher.fetch(v, sessionId, System.currentTimeMillis() / 1000))
            } catch (e: WolvyError) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(WolvyError(WolvyError.Code.FAILED, "Playback data could not be read.", "source_failed",
                    ScrubbedCause(Scrub.summary(e))))
            }
            main.post {
                if (released || gen != generation) return@post
                result.fold({ r ->
                    // Keep the token that worked (it may be a fresh one) for the next reload.
                    if (video === v) video = r.video
                    then(r.data)
                }) { fail(it as WolvyError) }
            }
        }
    }

    private fun apply(d: PlaybackData) {
        data = d
        tokenIssuedAt = SystemClock.elapsedRealtime()
        // Every media request carries the CDN token and the session id: the CDN edges derive
        // watch time and usage from their logs, keyed by the session id.
        mediaHttp.setDefaultRequestProperties(d.cdnHeaders)
    }

    private fun prepare(startMs: Long) {
        val v = video ?: return
        val d = data ?: return
        applyConstraints()
        val item = MediaItem.Builder()
            .setUri(d.manifestUrl(v))
            .setMimeType(MimeTypes.APPLICATION_MPD)
            .setDrmConfiguration(MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).setLicenseUri(d.licenceUrl).build())
            .build()
        exo.setMediaItem(item, startMs)
        exo.prepare()
        exo.playWhenReady = wantPlay && protectionConfirmed
        exo.volume = if (muted) 0f else userVolume
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    private fun reload() {
        val pos = exo.currentPosition.coerceAtLeast(0)
        recovery.onReload(pos / 1000.0)
        fetch(generation, data?.sessionId) { d ->
            apply(d)
            prepare(pos)
        }
    }

    // ---- errors and recovery -------------------------------------------------------------

    private fun onPlaybackError(error: PlaybackException) {
        // The original exception can hold request addresses and headers with tokens: errors that
        // leave the SDK carry a scrubbed summary instead.
        val cause = ScrubbedCause("${error.errorCodeName}: ${Scrub.summary(error)}")
        val d = data ?: return fail(WolvyError(WolvyError.Code.FAILED, "Playback failed.", "playback_failed", cause))
        val causeText = generateSequence(error as Throwable) { it.cause }
            .joinToString(" ") { "${it.javaClass.name} ${it.message ?: ""}" }
        val isDrm = ErrorKind.isDrm(error.errorCode, causeText)
        Log.w(TAG, "playback error ${error.errorCodeName}; drm=$isDrm")

        // App-Only on a software-DRM device: the licence server refuses by design. No retries.
        if (isDrm && d.appOnly && softwareDrm) {
            return fail(WolvyError(WolvyError.Code.DEVICE_NOT_SUPPORTED,
                "This video needs a device with hardware DRM.", "hardware_drm_required", cause))
        }
        val fresh = RecoveryPolicy.tokenFresh(tokenIssuedAt, d.licenceTokenTtlSeconds, SystemClock.elapsedRealtime())
        when (recovery.onError(isDrm, fresh)) {
            RecoveryPolicy.Action.STEP_DOWN -> {
                // The SD key is already in the licence: no new licence needed.
                quality.stepDown()
                applyConstraints()
                emitQuality()
                exo.prepare()
            }
            RecoveryPolicy.Action.RELOAD -> reload()
            RecoveryPolicy.Action.RELOAD_AND_STEP_DOWN -> {
                quality.stepDown()
                emitQuality()
                reload()
            }
            RecoveryPolicy.Action.FAIL -> fail(
                WolvyError(
                    if (ErrorKind.isNetwork(error.errorCode)) WolvyError.Code.NETWORK_ERROR else WolvyError.Code.PLAYBACK_ERROR,
                    "This video could not be played on this device.",
                    when {
                        isDrm -> "licence_refused"
                        ErrorKind.isNetwork(error.errorCode) -> "network"
                        else -> "playback_failed"
                    },
                    cause,
                ),
            )
        }
    }

    private fun fail(e: WolvyError) {
        if (fatal != null) return
        fatal = e
        exo.playWhenReady = false
        Log.w(TAG, e.toString())
        emit { it.onError(e) }
    }

    // ---- qualities -----------------------------------------------------------------------

    private fun onTracks(tracks: Tracks) {
        val heights = tracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }
            .filter { it > 0 }
        if (heights.isEmpty()) return
        if (!quality.onTracks(heights)) {
            return fail(WolvyError(WolvyError.Code.DEVICE_NOT_SUPPORTED,
                "This video has no quality this device can play securely.", "no_secure_quality"))
        }
        applyConstraints()
        emitQuality()
    }

    private fun applyConstraints() {
        val (maxH, minH) = quality.constraints()
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, maxH)
                .setMinVideoSize(0, minH),
        )
    }

    private fun emitQuality() {
        val q = getQualities()
        if (q == lastQualities) return
        lastQualities = q
        emit { it.onQualityChange(q) }
    }

    // ---- the tick: timeupdate, progress, captions ----------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        if (!readySent) return
        val t = currentTime
        val d = duration
        val playing = exo.isPlaying
        progress.tick(t, d, playing)
        recovery.onPosition(t)
        if (playing) emit { it.onTimeUpdate(t, d) }
        emitProgress(force = false)
        updateCue(t)
    }

    private fun emitProgress(force: Boolean) {
        val d = duration
        if (!progress.shouldEmit(d, SystemClock.elapsedRealtime(), force)) return
        val p = Progress(progress.percent(d), progress.seconds, d)
        emit { it.onProgress(p) }
    }

    private fun updateCue(t: Double) {
        val text = if (captionLanguage == null) null else VttParser.at(cues, t)?.text
        if (text == cueText) return
        cueText = text
        emit { it.onCaptionCue(text) }
    }

    private fun clearCaptions() {
        captionLanguage = null
        cues = emptyList()
        if (cueText != null) { cueText = null; emit { it.onCaptionCue(null) } }
    }

    // ---- the web Player API ----------------------------------------------------------------

    public fun play() {
        checkUsable()
        wantPlay = true
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.playWhenReady = protectionConfirmed
    }

    public fun pause() {
        checkUsable()
        wantPlay = false
        exo.playWhenReady = false
    }

    /** Jumps to [seconds] (clamped to the video). Returns the time jumped to. */
    public fun seek(seconds: Double): Double {
        checkUsable()
        val d = duration
        if (!readySent || d <= 0) throw WolvyError(WolvyError.Code.NOT_READY, "The video's length is not known yet.")
        if (seconds.isNaN()) throw WolvyError(WolvyError.Code.INVALID_ARGUMENT, "seconds must be a number.")
        val t = seconds.coerceIn(0.0, d)
        exo.seekTo((t * 1000).toLong())
        return t
    }

    public fun mute() {
        checkUsable()
        muted = true
        exo.volume = 0f
        emit { it.onVolumeChange(userVolume, true) }
    }

    public fun unmute() {
        checkUsable()
        muted = false
        exo.volume = userVolume
        emit { it.onVolumeChange(userVolume, false) }
    }

    /** [volume] from 0 to 1. */
    public fun setVolume(volume: Float) {
        checkUsable()
        if (volume.isNaN() || volume < 0f || volume > 1f) {
            throw WolvyError(WolvyError.Code.INVALID_ARGUMENT, "volume must be between 0 and 1.")
        }
        userVolume = volume
        if (!muted) exo.volume = volume
        emit { it.onVolumeChange(volume, muted) }
    }

    /** One of [PLAYBACK_RATES]: 0.5, 0.75, 1, 1.25, 1.5, 2. */
    public fun setPlaybackRate(rate: Float) {
        checkUsable()
        if (rate !in PLAYBACK_RATES) {
            throw WolvyError(WolvyError.Code.INVALID_ARGUMENT, "rate must be one of $PLAYBACK_RATES.")
        }
        exo.setPlaybackSpeed(rate)
    }

    /** A height from [getQualities] `available`, or null for automatic. */
    public fun setQuality(height: Int?) {
        checkUsable()
        if (quality.allHeights.isEmpty()) throw WolvyError(WolvyError.Code.NOT_READY, "The qualities are not known yet.")
        if (!quality.select(height)) {
            throw WolvyError(WolvyError.Code.INVALID_ARGUMENT, "$height is not one of ${quality.available}.")
        }
        applyConstraints()
        emitQuality()
    }

    /** A language from [getCaptions] `available`, or null (or "off") to turn captions off. */
    public fun setCaptions(language: String?) {
        checkUsable()
        val d = data ?: throw WolvyError(WolvyError.Code.NOT_READY, "The video is not loaded yet.")
        val v = video ?: throw WolvyError(WolvyError.Code.NOT_READY, "The video is not loaded yet.")
        if (language == null || language == "off") {
            if (captionLanguage != null) { clearCaptions(); emit { it.onCaptionsChange(null) } }
            return
        }
        if (d.captions.none { it.language == language }) {
            throw WolvyError(WolvyError.Code.INVALID_ARGUMENT, "\"$language\" is not one of the video's captions.")
        }
        clearCaptions()
        captionLanguage = language
        val url = d.captionUrl(v, language)
        val gen = generation
        io.execute {
            val parsed = try { VttParser.parse(httpGet(url)) } catch (e: Exception) {
                Log.w(TAG, "captions $language could not be loaded: ${e.message}")
                emptyList()
            }
            main.post { if (!released && gen == generation && captionLanguage == language) cues = parsed }
        }
        emit { it.onCaptionsChange(language) }
    }

    public val currentTime: Double get() = exo.currentPosition.coerceAtLeast(0) / 1000.0

    /** In seconds; 0 until known. */
    public val duration: Double
        get() {
            val d = exo.duration
            return if (d != C.TIME_UNSET && d > 0) d / 1000.0 else data?.duration ?: 0.0
        }

    public val paused: Boolean get() = !wantPlay
    public val isMuted: Boolean get() = muted
    public val volume: Float get() = userVolume
    public val playbackRate: Float get() = exo.playbackParameters.speed

    public fun getState(): State = State(currentTime, duration, paused, muted, userVolume, playbackRate)

    public fun getQualities(): Qualities =
        Qualities(quality.available, quality.selected, exo.videoFormat?.height?.takeIf { it > 0 })

    public fun getCaptions(): Captions = Captions(data?.captions ?: emptyList(), captionLanguage)

    public fun getProgress(): Progress { val d = duration; return Progress(progress.percent(d), progress.seconds, d) }

    /** Chapters, highlights, the viewer id and the account's player look, once loaded; null before. */
    public val videoInfo: VideoInfo?
        get() = data?.let { VideoInfo(it.chapters, it.moments, it.viewerId, it.watermark, it.playerColor, it.poster) }

    public fun addListener(listener: WolvyPlayerListener) { listeners.addIfAbsent(listener) }
    public fun removeListener(listener: WolvyPlayerListener) { listeners.remove(listener) }

    /** Stops and frees the player. Every call after this throws [WolvyError.Code.DESTROYED]. */
    public fun release() {
        if (released) return
        released = true
        generation++
        main.removeCallbacks(ticker)
        exo.release()
        io.shutdownNow()
        listeners.clear()
    }

    // ---- for WolvyPlayerView -----------------------------------------------------------------

    /** Set by the view once FLAG_SECURE is confirmed on its window; nothing plays before. */
    internal fun setProtectionConfirmed(confirmed: Boolean) {
        protectionConfirmed = confirmed
        if (!released) exo.playWhenReady = wantPlay && confirmed && fatal == null
    }

    internal fun failFromView(e: WolvyError) = fail(e)

    internal fun emitFullscreen(fullscreen: Boolean) = emit { it.onFullscreenChange(fullscreen) }

    internal val lastError: WolvyError? get() = fatal

    // ---- helpers -----------------------------------------------------------------------------

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Call WolvyPlayer on the main thread." }
    }

    private fun checkUsable() {
        checkMain()
        if (released) throw destroyed()
        fatal?.let { throw WolvyError(it.code, it.message ?: "", it.reason, it) }
    }

    private fun destroyed() = WolvyError(WolvyError.Code.DESTROYED, "release() was called on this player.")

    private inline fun emit(crossinline call: (WolvyPlayerListener) -> Unit) {
        for (l in listeners) {
            try { call(l) } catch (e: Exception) { Log.e(TAG, "a WolvyPlayerListener threw", e) }
        }
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", userAgent)
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** What [WolvyPlayer.videoInfo] returns. [playerColor] is 0xAARRGGBB or null. */
public data class VideoInfo(
    val chapters: List<Chapter>,
    val moments: List<Moment>,
    val viewerId: String?,
    val watermark: WatermarkConfig,
    val playerColor: Int?,
    val poster: String?,
)
