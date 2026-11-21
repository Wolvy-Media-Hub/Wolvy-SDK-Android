# Changelog

## 0.1.0

First release of `net.wolvy:wolvy-player`.

- `WolvyPlayer` and `WolvyPlayerView` on Media3 1.11.1: Wolvy's Widevine playback through `wolvy.net/api/app_render_video`, with the app's package name checked against the account's Allowed apps.
- The web Player API's methods, events and error codes; `load` and `retry` for apps; `onCaptionCue` for apps that draw their own captions.
- Short viewer tokens: `WolvyVideo` takes a `ViewerTokenProvider`, asked before a token expires and once if Wolvy refuses it. Tokens are removed from error messages and causes.
- Screen protection: `FLAG_SECURE` on the player's window (counted per window, so several views and a hand-over between views keep it on) and a secure video surface; nothing plays until it is confirmed.
- Quality by DRM level: every quality on Widevine L1, 480p and below on L2 and L3; App-Only accounts refuse software DRM (`hardware_drm_required`).
- Recovery: one step down to SD on a licence error; fresh playback data and a resume at the same position after a network drop or an expired token (3 times, counted again after a minute of good playback).
- Viewer watermark (static or moving), WebVTT captions, chapter marks, the account's player colour, a quality, captions and speed menu, and a bare view without controls.
- Android 7.0 (API 24) and later. Apache License 2.0.
