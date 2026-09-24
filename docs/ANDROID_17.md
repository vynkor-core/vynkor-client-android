# Android 17 (API 37) support

The app targets and compiles against API 37 (AGP 9.4, Gradle 9.6). What that
changed, and where it is handled:

| Android 17 change | Effect on this app | Handling |
|---|---|---|
| **Local network permission** (`ACCESS_LOCAL_NETWORK`, NEARBY_DEVICES group) — mandatory for target 37 | Without it every TCP connect to a LAN host is black-holed: the socket just times out | Declared in the manifest; requested with the service permissions (`AgentPermissions`), shown in the setup wizard and on Settings → Capabilities. A failed connect without the grant is reported as the missing permission, not a timeout. The Rust core bounds the connect to 10 s either way. |
| **Background audio hardening** — playback, focus and volume APIs fail silently for background apps without a while-in-use foreground service | Host TTS on the phone speaker | The agent service is started from a visible activity (WIU) with explicit `connectedDevice\|mediaPlayback` types. Audio focus is transient per utterance (`GAIN_TRANSIENT_MAY_DUCK`) instead of held for the service lifetime. |
| **Background activity launch hardening** | `launcher.open` from the host while the app is in the background | Opens directly only in the foreground; otherwise posts a tap-to-open notification (the old code reported success for a launch the OS dropped). |
| **Implicit URI grants restricted** (enforced for target 18) | Camera capture into the FileProvider | `TakePictureWithGrants` adds explicit read/write grants. |
| **Certificate Transparency on by default, ECH** | Platform TLS only | The host link uses rustls in the Rust core with cert pinning — unaffected. |
| **Widget RemoteViews bitmap cap** | Status widget | No bitmaps in the widget — unaffected. |
| **Static final fields unmodifiable via reflection / JNI** | — | Not used by the app. |
| **Large-screen orientation/resizability ignored (sw ≥ 600dp)** | QR scanner is portrait-locked | Acceptable: ignored on tablets, still locked on phones. |

Also on this target: predictive back is enabled (`enableOnBackInvokedCallback`),
and Robolectric runs the unit tests on API 37 (needs
`--add-opens java.base/jdk.internal.access`, set in `app/build.gradle.kts`).

Testing the local-network block on an Android 16 device:

```bash
adb shell am compat enable RESTRICT_LOCAL_NETWORK dev.vynkor.agent && adb reboot
```
