# Microphone Audio Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable voice input in Remote DSH Web by declaring `RECORD_AUDIO` permission and bridging browser `getUserMedia({ audio: true })` requests through Android's `WebChromeClient.onPermissionRequest` and runtime activity result API.

**Architecture:** Add `android.permission.RECORD_AUDIO` to `AndroidManifest.xml`. In `WebViewContainer.kt`, wire `rememberLauncherForActivityResult` with `RequestPermission` contract and override `WebChromeClient.onPermissionRequest` to grant `RESOURCE_AUDIO_CAPTURE` on user consent. Bump version to 1.0.5 (code 6) and prepare Fastlane release metadata.

**Tech Stack:** Kotlin, Android SDK 36 (minSdk 24), Jetpack Compose, Android WebView, Gradle.

## Global Constraints
- Target package: `io.github.molove.remotedshweb` (`com.example.dshclient`).
- Cleanroom permission handling: Only request `RESOURCE_AUDIO_CAPTURE` when web requests it. Deny all other unhandled resources.
- Do not prompt upfront on app launch; prompt strictly on-demand.
- No binary bloat, no third-party audio recording libraries.

---

### Task 1: Declare `RECORD_AUDIO` Permission and Prepare Fastlane Changelog

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `fastlane/metadata/android/en-US/changelogs/6.txt`

**Interfaces:**
- Consumes: None
- Produces: OS permission declaration for `android.permission.RECORD_AUDIO`

- [ ] **Step 1: Add permission to AndroidManifest.xml**
Add `<uses-permission android:name="android.permission.RECORD_AUDIO" />` alongside existing permissions in `app/src/main/AndroidManifest.xml`.

- [ ] **Step 2: Create Fastlane changelog**
Create `fastlane/metadata/android/en-US/changelogs/6.txt` with release description:
```text
Added microphone audio capture permission and WebView bridge for DSH voice input.
```

- [ ] **Step 3: Verification**
Verify manifest parses cleanly by running `./gradlew checkDebugAarMetadata`.

- [ ] **Step 4: Commit**
Commit changes with message `feat(permissions): add RECORD_AUDIO permission and v1.0.5 changelog`.

---

### Task 2: Implement `onPermissionRequest` Audio Bridge in `WebViewContainer.kt`

**Files:**
- Modify: `app/src/main/java/com/example/dshclient/ui/WebViewContainer.kt`

**Interfaces:**
- Consumes: `android.permission.RECORD_AUDIO` from Task 1
- Produces: `WebChromeClient.onPermissionRequest` implementation granting `PermissionRequest.RESOURCE_AUDIO_CAPTURE`

- [ ] **Step 1: Add necessary imports in WebViewContainer.kt**
```kotlin
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
```

- [ ] **Step 2: Add permission state and ActivityResultLauncher inside WebViewContainer**
In `@Composable fun WebViewContainer`, retrieve `LocalContext.current`, manage `pendingPermissionRequest` state, and create `recordAudioLauncher`:
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

- [ ] **Step 3: Implement onPermissionRequest & onPermissionRequestCanceled in WebChromeClient**
In `webChromeClient = object : WebChromeClient() { ... }`:
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

- [ ] **Step 4: Clean up pending request on WebView release**
In `onRelease` callback of `AndroidView`:
```kotlin
onRelease = { webView ->
    pendingPermissionRequest?.deny()
    pendingPermissionRequest = null
    webView.destroy()
    onRelease?.invoke()
}
```

- [ ] **Step 5: Verification**
Run `./gradlew compileDebugKotlin` to verify compilation.

- [ ] **Step 6: Commit**
Commit changes with message `feat(webview): bridge WebChromeClient audio capture to Android runtime permission`.

---

### Task 3: Version Bump to v1.0.5 (code 6) and Unit Verification

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: Completed implementations from Tasks 1 and 2
- Produces: Versioned build configuration

- [ ] **Step 1: Bump versionCode and versionName**
In `app/build.gradle.kts`:
```kotlin
versionCode = 6
versionName = "1.0.5"
```

- [ ] **Step 2: Run unit tests and lint**
Run `./gradlew testDebugUnitTest lintDebug`. Ensure all tests pass with 0 errors.

- [ ] **Step 3: Commit**
Commit changes with message `chore(release): bump version to 1.0.5 (versionCode 6)`.

---

### Task 4: Assemble Debug APK & Export to Staging

**Files:**
- Build output: `app/build/outputs/apk/debug/app-debug.apk`
- Export to: `/mnt/dropbox/RemoteDshWeb-v1.0.5-debug.apk` and `/mnt/pn-g/RemoteDshWeb-latest.apk`

- [ ] **Step 1: Assemble Debug APK**
Run `./gradlew assembleDebug`.

- [ ] **Step 2: Inspect APK manifest permissions**
Verify `android.permission.RECORD_AUDIO` is present in the final merged APK:
`aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep RECORD_AUDIO`

- [ ] **Step 3: Copy to DropBox staging and pn-g**
Copy `app-debug.apk` to `/mnt/dropbox/` and `/mnt/pn-g/`.

- [ ] **Step 4: Commit and Push to Gitea**
Push all commits to `origin/master`.
