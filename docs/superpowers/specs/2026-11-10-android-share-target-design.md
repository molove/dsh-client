# Spec: Android Share Target for Remote DSH Web

**Date:** 2026-11-10  
**Status:** Approved  
**Author:** G  
**Target:** `io.github.molove.remotedshweb` (`~/Projects/dsh-client`)

---

## 1. Problem & Use Case
Piers is conducting a physical decluttering run with Q (`dsh-web` on `chez-q`). Taking photos of items/boxes or copying item links on his Samsung Galaxy A54 and sending them to Q currently requires either manual clipboard copy-pasting or file management. Making Remote DSH Web an Android system Share Sheet target allows one-tap sharing of images and text directly into Q's active chat session.

---

## 2. Architecture & Components

### 2.1 Android Manifest (`AndroidManifest.xml`)
Register two `<intent-filter>` blocks under `MainActivity`:
- `android.intent.action.SEND` for MIME types `text/plain`, `text/*`, `image/*`.
- `android.intent.action.SEND_MULTIPLE` for MIME type `image/*`.

### 2.2 Share Payload Extraction (`SharePayloadHelper.kt`)
A dedicated helper to extract and normalize incoming share intents:
- **Text**: Read `Intent.EXTRA_TEXT` (and `Intent.EXTRA_SUBJECT` if present).
- **Single Image**: Resolve `content://` URI from `Intent.EXTRA_STREAM`, read bytes via `ContentResolver`, detect MIME type, and encode as base64 data URL.
- **Multiple Images**: Resolve all URIs in `Intent.EXTRA_STREAM`, encode each into base64.
- **Clipboard Sync**: Also copy the incoming content to Android's `ClipboardManager` as an instant fallback.

### 2.3 ViewModel & State (`MainScreenViewModel.kt`)
- Add `pendingShare: MutableStateFlow<SharePayload?>`.
- When an intent arrives via `onCreate` or `onNewIntent`, parse payload and set `pendingShare`.
- Clear `pendingShare` once consumed and injected into the WebView.

### 2.4 WebView Injection (`WebViewContainer.kt` & `WebViewHelper.kt`)
- In `WebViewClient.onPageFinished`, or dynamically when `pendingShare` updates on an already loaded WebView:
  - If text: Find prompt (`textarea, [contenteditable="true"]`), insert text, and trigger input events.
  - If image: Construct `File` / `Blob` from base64 data URL, construct synthetic `ClipboardEvent('paste')` with `DataTransfer`, and dispatch to prompt.
- Toast confirmation: Show `"Shared to Q"` Toast.

---

## 3. Testing & Verification
1. **Unit tests**: `SharePayloadHelperTest` verifying extraction of single text, single image URI, and multiple image URIs.
2. **Build verification**: `./gradlew assembleDebug` succeeds cleanly.
3. **Live device verification**:
   - Install APK on Samsung Galaxy A54 via `adb install -r`.
   - Send text via `adb shell am start -a android.intent.action.SEND -t "text/plain" --es android.intent.extra.TEXT "Declutter test note" -n io.github.molove.remotedshweb/com.example.dshclient.MainActivity`.
   - Send image via `adb shell am start -a android.intent.action.SEND -t "image/jpeg" --eu android.intent.extra.STREAM "<image_uri>" -n io.github.molove.remotedshweb/com.example.dshclient.MainActivity`.
   - Verify prompt updates in DSH Web.
