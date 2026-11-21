# Changelog

## 0.2.0

- **App protection.** When the account sets up protection for the app (dashboard: Settings → Protection → Allowed apps → Protection), the app proves it is genuine before playback with the customer's own Google Play Integrity (a Classic request under the project linked in the app's Play Console). Wolvy answers a 6-hour app key, and the SDK signs `app_render_video` and every licence request with it. A proven app is treated like Wolvy Player: with App-Only on it plays on phones with software DRM too. Wolvy's checks are Wolvy Player's: installed from Google Play, no rooted phone, emulator or unlocked bootloader, and the signing certificate the customer listed.
- Without the setup, or when the proof fails (no Google Play services, a refusal), the app plays unproven, as in 0.1.0, unless the account only allows proven apps. New error reasons: `attestation_required`, `device_compromised` (code `device_not_supported`), `app_not_genuine`, `attestation_failed`.
- `State.attested` and `PlaybackData.attested`; `AppRenderVideoSource(context, sdk, apiBase)` for wrappers (the `String` constructor does not prove the app).
- Depends on `com.google.android.play:integrity` 1.5.0.

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
