# Android Share Target Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable Remote DSH Web (`io.github.molove.remotedshweb`) to receive shared text and images from Android's system Share Sheet and inject them directly into Q's active chat session.

**Architecture:** Android's `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intents are intercepted by `MainActivity`, converted into a normalized `SharePayload` via `SharePayloadHelper`, staged in the system clipboard, and passed to `MainScreenViewModel.pendingShare`. When DSH Web is loaded in `WebViewContainer`, a synthetic JS injection inserts the text or dispatches a synthetic `ClipboardEvent('paste')` with a `DataTransfer` file item directly into the prompt element.

**Tech Stack:** Kotlin, Android SDK 36 (minSdk 24), Jetpack Compose, Android WebView, JUnit 4, Robolectric.

## Global Constraints
- Target package: `io.github.molove.remotedshweb` (`com.example.dshclient`)
- Maintain existing `singleTask` launchMode for `MainActivity`
- Zero regression on existing SSH tunnel and WebView styling

---

### Task 1: Data Model, Extraction Helper, and Manifest Filters

**Files:**
- Create: `app/src/main/java/com/example/dshclient/data/SharePayload.kt`
- Create: `app/src/main/java/com/example/dshclient/data/SharePayloadHelper.kt`
- Create: `app/src/test/java/com/example/dshclient/data/SharePayloadHelperTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface SharePayload {
      data class Text(val text: String) : SharePayload
      data class Image(val mimeType: String, val base64Data: String, val filename: String) : SharePayload
      data class MultipleImages(val images: List<Image>) : SharePayload
  }
  object SharePayloadHelper {
      fun parseIntent(context: Context, intent: Intent?): SharePayload?
      fun copyToClipboard(context: Context, payload: SharePayload)
  }
  ```

- [ ] **Step 1: Write unit tests in `SharePayloadHelperTest.kt`**
- [ ] **Step 2: Run test to confirm it fails (missing implementation)**
- [ ] **Step 3: Implement `SharePayload.kt` and `SharePayloadHelper.kt`**
- [ ] **Step 4: Run unit tests and confirm they pass**
- [ ] **Step 5: Add `<intent-filter>` blocks to `AndroidManifest.xml`**
- [ ] **Step 6: Commit changes**

---

### Task 2: ViewModel State & Intent Wiring in MainActivity

**Files:**
- Modify: `app/src/main/java/com/example/dshclient/ui/main/MainScreenViewModel.kt`
- Modify: `app/src/main/java/com/example/dshclient/MainActivity.kt`
- Modify: `app/src/test/java/com/example/dshclient/ui/main/MainScreenViewModelTest.kt` (if exists or create)

**Interfaces:**
- Consumes: `SharePayload`, `SharePayloadHelper`
- Produces:
  ```kotlin
  // MainScreenViewModel:
  val pendingShare: StateFlow<SharePayload?>
  fun setPendingShare(payload: SharePayload?)
  fun consumePendingShare(): SharePayload?
  ```

- [ ] **Step 1: Add `pendingShare` StateFlow and handlers to `MainScreenViewModel.kt`**
- [ ] **Step 2: Update `MainActivity.kt` to intercept intents in `onCreate()` and `onNewIntent()`**
- [ ] **Step 3: Verify with unit test / `./gradlew testDebugUnitTest`**
- [ ] **Step 4: Commit changes**

---

### Task 3: JavaScript Injection & WebView Consumption

**Files:**
- Modify: `app/src/main/java/com/example/dshclient/ui/WebViewContainer.kt`
- Modify: `app/src/main/java/com/example/dshclient/ui/main/MainScreen.kt`

**Interfaces:**
- Consumes: `pendingShare` from `MainScreenViewModel`
- Produces: JS injection into WebView to insert text or trigger synthetic `paste` event.

- [ ] **Step 1: Implement `buildShareInjectionJs(payload: SharePayload)` in `WebViewHelper`**
- [ ] **Step 2: Wire `pendingShare` collection in `MainScreen` / `WebViewContainer`**
- [ ] **Step 3: Trigger injection when WebView page is loaded (`onPageFinished` or on state change)**
- [ ] **Step 4: Show Android Toast (`Shared to Q`) upon injection**
- [ ] **Step 5: Verify build with `./gradlew assembleDebug`**
- [ ] **Step 6: Commit changes**

---

### Task 4: End-to-End Verification on Samsung Galaxy A54

**Files:**
- None (Build & Deployment)

- [ ] **Step 1: Install debug APK to test phone via ADB (`adb install -r ...`)**
- [ ] **Step 2: Test sharing text via `adb shell am start -a android.intent.action.SEND ...`**
- [ ] **Step 3: Test sharing image via `adb shell am start -a android.intent.action.SEND ...`**
- [ ] **Step 4: Verify text/image appears in DSH Web chat prompt**
