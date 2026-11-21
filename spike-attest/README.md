# Spike A: Play Integrity for a customer's app (phase 5)

App id `net.wolvy.spike.attest`: a customer-like app that is not linked to any Cloud project in
Play Console. It asks Play Integrity for a token under Wolvy Player's Cloud project
(`406707116001`, editable), Standard or Classic. It then shows a report: the package, the
SHA-256 of its signing certificate, the request hash or nonce, and the token.

**Question it answers** (`future plans/sdk-plan/phase-5-attestation.md`, 2.7): can Wolvy's
project decode a token from an app it isn't linked to? Does the verdict carry the app's
package name and certificate, and both phones' device integrity?

**Decode on the Mac:**

```bash
GOOGLE_SA_KEY=/path/outside/the/repos/key.json node tools/decode-integrity.mjs report.txt
```

- `report.txt` is the app's "Copy report" text.
- `--as <package>` decodes under another package name, to see how Google answers a mismatch.
- The script prints the verdict and the design's checks. It never prints anything from the key
  file.

**Build** (JDK 17, Android SDK 36):

```bash
./gradlew assembleRelease
```

The APK is `app/build/outputs/apk/release/app-release.apk`. It is signed with the debug key,
for test phones only. That key's SHA-256 is
`4B:0A:03:C7:C4:2F:AB:0D:0B:21:DE:FC:80:D3:07:93:15:E7:CA:FF:0A:C9:4E:A6:63:4F:CD:A3:AC:A8:40:9B`.

**Spike A2 (the customer's own Play Integrity):**
- Leave the project field empty: the request then runs under the project linked in Play
  Console.
- Read the Classic token locally with that app's self-managed keys:

```bash
PI_DECRYPTION_KEY_FILE=… PI_VERIFICATION_KEY_FILE=… node tools/decrypt-integrity.mjs report.txt
```

- `--self-test` checks the decryption and verification with throwaway keys.
- `./gradlew bundleRelease -PuploadKeyProps=/path/upload.properties` builds the bundle for
  Play's internal testing. Keep the upload key outside the repository.

**Not the SDK.** This is a separate Gradle project, left out of the library build.
