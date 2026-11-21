# Wolvy Android SDK

**0.1.0, ready for Maven Central (not released yet).** The Flutter and React Native SDKs are built on it.

Plays Wolvy's DRM-protected video (DASH and Widevine, through Media3) in your own Android app, with the viewer watermark, screen-capture protection and the account's quality rules, and with the same methods and events as Wolvy's web Player API.

- Android 7.0 (API 24) and later.
- Your app's package name must be on your account's **Allowed apps** (Wolvy dashboard → Settings → Protection). An empty list lets no app play.
- The viewer token is made on **your server**: `POST /v1/videos/{id}/playback-tokens` on the Developer API, or `signViewerToken` in the [PHP](https://github.com/Wolvy-Media-Hub/Wolvy-SDK-PHP) and [Node.js](https://github.com/Wolvy-Media-Hub/Wolvy-SDK-Node) SDKs. Nothing secret goes into the app.

## Install

Once released on Maven Central (not yet):

```kotlin
dependencies {
    implementation("net.wolvy:wolvy-player:0.1.0")
}
```

## Use

```kotlin
val player = WolvyPlayer(context)                 // asks wolvy.net/api/app_render_video with your package name
playerView.player = player                        // a WolvyPlayerView in your layout
player.addListener(object : WolvyPlayerListener {
    override fun onProgress(progress: Progress) {
        if (progress.percent >= 90) markLessonComplete()   // seconds actually watched, each once
    }
    override fun onError(error: WolvyError) = log(error.code.value, error.reason)
})
player.load(WolvyVideo(videoId, accountId, viewerTokenFromYourServer))
// …
playerView.player = null
player.release()
```

`WolvyVideo.fromEmbedUrl(embedUrl)` reads the three values from the `embed_url` that `POST /v1/videos/{id}/playback-tokens` returns.

### Short viewer tokens (recommended)

A viewer token is good for any video of your account until it expires, so keep it short (for example 10 minutes) and give the player a way to get a fresh one from your server:

```kotlin
val video = WolvyVideo(videoId, accountId, tokenFromYourServer) { v ->
    myApi.fetchViewerToken(v.videoId)          // runs on a background thread; signed on your server
}
player.load(video)
```

The player asks for a fresh token before it talks to Wolvy when the one it has expires within a minute (it reads the expiry from the token; only Wolvy verifies it), and once more if Wolvy refuses the token. Without a provider, the token must outlive the viewing: a reload after an error needs it again.

Tokens never leave the SDK in errors: the viewer, licence and CDN tokens are removed from `WolvyError` messages and causes, and the token fields of `PlaybackData` are not readable from app code. Never log the token you pass in.

Create, call and release the player on the main thread.

## What it does for you

| | |
|---|---|
| Screen protection | `WolvyPlayerView` sets `FLAG_SECURE` on its window and nothing plays until that is confirmed. **The flag belongs to the whole window:** while the player is on screen, screenshots and recordings of the app's whole screen are black. If the flag cannot be set, the player refuses (`playback_error`, reason `screen_protection_unavailable`). The video surface is a secure `SurfaceView` |
| Quality | Phones with hardware DRM (Widevine L1) get every quality. Phones with software DRM (L2, L3) are offered 480p and below from the start. **Wolvy's licence server decides this:** it never sends the HD keys to software DRM; the SDK only avoids asking for keys it will not get |
| App-Only | When the account has App-Only on, a software-DRM phone gets no keys at all: `device_not_supported` (reason `hardware_drm_required`) |
| Recovery | A DRM error steps down to SD once. A stale licence token, a network drop or an expired CDN token: fresh playback data for the same session, then playback resumes at the same position, up to 3 times (counted again after a minute of good playback) |
| Analytics | Every media request carries the playback session id, so watch time and usage appear in your dashboard as for the web player |
| Watermark | The verified viewer id from the token, static or moving, inside the picture, as set in the dashboard |
| Captions | Fetched and drawn by the SDK (WebVTT) |
| Chapters | Marks on the seek bar; `player.videoInfo` has chapters and highlights |
| Look | The account's player colour; quality, captions and speed in a menu. `playerView.showControls = false` for a bare view with your own controls |
| Fullscreen | The button reports `onFullscreenChange(true/false)`; your activity resizes the view (the sample shows how) |

## API

The web Player API's names, so the same code ideas work in every Wolvy SDK.

| Methods | Notes |
|---|---|
| `load(video, autoplay = true)` | Android only: starts a video (`video` may carry a `ViewerTokenProvider`) |
| `play()`, `pause()` | |
| `seek(seconds)` | Returns the time jumped to; `not_ready` before the length is known |
| `mute()`, `unmute()`, `setVolume(0..1)` | |
| `setPlaybackRate(rate)` | 0.5, 0.75, 1, 1.25, 1.5 or 2 |
| `setQuality(height or null)` | A height from `getQualities().available`; null = automatic |
| `setCaptions(language or null)` | A language from `getCaptions().available`; null = off |
| `currentTime`, `duration`, `paused`, `isMuted`, `volume`, `playbackRate` | Properties |
| `getState()`, `getQualities()`, `getCaptions()`, `getProgress()` | |
| `retry()` | Android only: after an error |
| `release()` | The web `destroy()` |

| Events (`WolvyPlayerListener`) | |
|---|---|
| `onReady(state)`, `onPlay`, `onPause`, `onEnded`, `onSeeked` | |
| `onTimeUpdate(currentTime, duration)` | About 4 times a second while playing |
| `onProgress(progress)` | Share of the video's seconds played, each counted once, with one decimal: the same numbers as the web |
| `onVolumeChange`, `onRateChange`, `onQualityChange`, `onCaptionsChange`, `onFullscreenChange` | |
| `onError(error)` | `error.code` is one of the web codes: `network_error`, `playback_error`, `device_not_supported`, `not_ready`, `invalid_argument`, `destroyed`, `failed`; `error.reason` is the server's code (`app_not_allowed`, `viewer_token_required`, `update_required`…) or the device's |
| `onCaptionCue(text)` | Android only, for apps that draw their own captions |

## Another source of playback data

`WolvyPlayer(context, source)` takes any `PlaybackSource`. The default, `AppRenderVideoSource`, posts to `https://wolvy.net/api/app_render_video` with `X-Wolvy-Platform: android`, `X-Wolvy-App-Id` (the package name) and `X-Wolvy-SDK: wolvy-android/<version>`. A source answers with `PlaybackData.fromJson(json)` from an answer of the same shape.

## Build

JDK 17, Android SDK 36:

```bash
./gradlew :wolvy-player:testDebugUnitTest :wolvy-player:assembleRelease :sample:assembleRelease
```

`sample/` is a small app that plays the `embed_url` you paste and logs every event; add `net.wolvy.player.sample` to a test account's Allowed apps. `spike/` is the phase 3 step 1 test app (separate Gradle project).

Other Wolvy SDKs: [web](https://github.com/Wolvy-Media-Hub/Wolvy-SDK) · [PHP](https://github.com/Wolvy-Media-Hub/Wolvy-SDK-PHP) · [Node.js](https://github.com/Wolvy-Media-Hub/Wolvy-SDK-Node) · [iOS](https://github.com/Wolvy-Media-Hub/Wolvy-SDK-iOS) · [Flutter](https://github.com/Wolvy-Media-Hub/Wolvy-SDK-Flutter) · [React Native](https://github.com/Wolvy-Media-Hub/Wolvy-SDK-React-Native)

## Licence

Apache License 2.0 ([LICENSE](LICENSE)).
