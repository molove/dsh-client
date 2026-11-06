# Remote DSH Web: Microphone Audio Capture Design Spec

- **Date**: 2026-11-06
- **Status**: Approved
- **Target**: `io.github.molove.remotedshweb` (`dsh-client`)
- **Author**: G & Piers

## 1. Context & Motivation
DSH now supports voice input. The DSH web frontend captures user speech using the browser MediaDevices API (`navigator.mediaDevices.getUserMedia({ audio: true })`).

When rendered inside Remote DSH Web's Android WebView:
1. Android's `WebView` delegates web-side permission requests to `WebChromeClient.onPermissionRequest(request: PermissionRequest)`.
2. By default, `WebChromeClient` denies all permission requests unless explicitly overridden and granted.
3. The host Android application must also hold the `android.permission.RECORD_AUDIO` permission granted by the OS user.
4. Currently, `AndroidManifest.xml` does not declare `RECORD_AUDIO`, and `WebViewContainer.kt` does not override `onPermissionRequest`, causing voice input to fail with `NotAllowedError`.

## 2. Goals & Non-Goals
### Goals
- Enable speech/voice input in DSH web via WebView audio capture.
- Implement an on-demand permission flow: only prompt the user for microphone access when they tap the voice input button in DSH.
- Safely bridge the Android runtime permission result to the WebView's `PermissionRequest` (`grant` or `deny`).
- Handle request cancellations cleanly to avoid memory leaks.
- Increment app version to `v1.0.5` (`versionCode 6`) and prepare Fastlane release metadata.

### Non-Goals
- Prompting for microphone access upfront during app startup.
- Video capture or camera permissions (`RESOURCE_VIDEO_CAPTURE`).
- Custom audio recording in native Kotlin (audio capture is managed by the browser engine inside WebView).

## 3. Detailed Architecture

### 3.1 Manifest Declaration
In `app/src/main/AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

### 3.2 WebView Permission Bridge (`WebViewContainer.kt`)
Within `@Composable fun WebViewContainer`:

1. **State & ActivityResultLauncher**:
   ```kotlin
   val context = LocalContext.current
   var pendingPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }

   val recordAudioLauncher = rememberLauncherForActivityResult(
       contract = ActivityResultContracts.RequestPermission()
   ) { isGranted ->
       pendingPermissionRequest?.let { req ->
           if (isGranted) {
               req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
           } else {
               req.deny()
           }
       }
       pendingPermissionRequest = null
   }
   ```

2. **WebChromeClient Overrides**:
   ```kotlin
   override fun onPermissionRequest(request: PermissionRequest?) {
       if (request == null) return
       val hasAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
       if (!hasAudio) {
           request.deny()
           return
       }

       val hasRecordAudioPermission = ContextCompat.checkSelfPermission(
           context,
           Manifest.permission.RECORD_AUDIO
       ) == PackageManager.PERMISSION_GRANTED

       if (hasRecordAudioPermission) {
           request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
       } else {
           pendingPermissionRequest = request
           recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
       }
   }

   override fun onPermissionRequestCanceled(request: PermissionRequest?) {
       super.onPermissionRequestCanceled(request)
       if (pendingPermissionRequest == request) {
           pendingPermissionRequest = null
       }
   }
   ```

3. **Lifecycle / Cleanup**:
   In `onRelease`, if `pendingPermissionRequest` is non-null, deny it and reset to null to prevent leaked references.

### 3.3 Versioning & Release Metadata
- `app/build.gradle.kts`:
  - `versionCode = 6`
  - `versionName = "1.0.5"`
- `fastlane/metadata/android/en-US/changelogs/6.txt`:
  - "Added microphone audio capture permission and WebView bridge for DSH voice input."

## 4. Verification & Testing
1. **Compilation**: `./gradlew compileDebugSources testDebugUnitTest`
2. **Lint & Checks**: `./gradlew lintDebug`
3. **Packaging**: `./gradlew assembleDebug`
4. **End-to-End On-Device Test (Samsung Galaxy A54)**:
   - Install `app-debug.apk`.
   - Connect to DSH over WireGuard/SSH tunnel.
   - Tap the microphone button in DSH web UI.
   - Verify Android system permission prompt appears ("Allow Remote DSH Web to record audio?").
   - Grant permission.
   - Verify speech is captured and converted to text in DSH prompt box.
   - Re-tap microphone button to verify audio capture starts immediately without re-prompting.
