# Spike: native Android host (baseline)

App id `net.wolvy.spike.android`. A plain activity with the player view: if protected video shows anywhere, it shows here.

The three spikes share one native view, `SpikePlayerView` (Kotlin): it calls `https://wolvy.net/api/app_render_video` with the app's package name, plays the DASH manifest with Widevine through Media3 1.11 on a secure `SurfaceView` (CDN token and session id as headers, as Wolvy Player does), and shows the Widevine level, the decoder Media3 picked (`.secure` = hardware path), the resolution, frames drawn and errors. **Copy report** copies that without any token. The window has `FLAG_SECURE`.

Input: the `embed_url` from `POST /v1/videos/{id}/playback-tokens` (`https://embed.wolvy.stream/{video}/{base64 account}?vt=…`). The app id must be on the account's Allowed apps.

**Not the SDK.** A throwaway test bench for phase 3, step 1 of the SDK plan: can hardware-protected (Widevine L1) video be shown in this kind of app? The SDK itself comes next, in `Wolvy-SDK-Android`.

Build (JDK 17, Android SDK 36): `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (signed with the debug key, for test phones only).
