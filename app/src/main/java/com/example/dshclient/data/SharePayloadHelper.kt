package com.example.dshclient.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.InputStream
import java.util.Base64

object SharePayloadHelper {
    private const val TAG = "SharePayloadHelper"
    private const val MAX_IMAGE_BYTES = 15 * 1024 * 1024 // 15MB safety limit

    fun parseIntent(context: Context, intent: Intent?): SharePayload? {
        if (intent == null) return null
        val action = intent.action ?: return null

        return when (action) {
            Intent.ACTION_SEND -> handleActionSend(context, intent)
            Intent.ACTION_SEND_MULTIPLE -> handleActionSendMultiple(context, intent)
            else -> null
        }
    }

    private fun handleActionSend(context: Context, intent: Intent): SharePayload? {
        val type = intent.type ?: ""

        // Image share
        if (type.startsWith("image/") || intent.hasExtra(Intent.EXTRA_STREAM)) {
            val streamUri = getParcelableExtraCompat<Uri>(intent, Intent.EXTRA_STREAM)
            if (streamUri != null) {
                val image = readImageUri(context, streamUri, type)
                if (image != null) {
                    return image
                }
            }
        }

        // Text share (also fallback if image stream could not be resolved)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()

        if (!text.isNullOrBlank()) {
            return SharePayload.Text(text)
        }

        return null
    }

    private fun handleActionSendMultiple(context: Context, intent: Intent): SharePayload? {
        val uris = getParcelableArrayListExtraCompat<Uri>(intent, Intent.EXTRA_STREAM) ?: return null
        val type = intent.type ?: "image/*"

        val images = uris.mapNotNull { uri ->
            readImageUri(context, uri, type)
        }

        return if (images.isNotEmpty()) {
            SharePayload.MultipleImages(images)
        } else {
            null
        }
    }

    private fun readImageUri(context: Context, uri: Uri, fallbackMime: String): SharePayload.Image? {
        return try {
            val mimeType = context.contentResolver.getType(uri) ?: fallbackMime.takeIf { it != "*/*" && it.startsWith("image/") } ?: "image/jpeg"
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                Log.w(TAG, "Unable to open input stream for URI: $uri")
                return null
            }

            inputStream.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > MAX_IMAGE_BYTES) {
                    Log.w(TAG, "Image exceeds maximum size: ${bytes.size} bytes")
                }
                val base64 = Base64.getEncoder().encodeToString(bytes)
                val ext = when {
                    mimeType.contains("png") -> "png"
                    mimeType.contains("webp") -> "webp"
                    mimeType.contains("gif") -> "gif"
                    else -> "jpg"
                }
                SharePayload.Image(
                    mimeType = mimeType,
                    base64Data = base64,
                    filename = "shared_${System.currentTimeMillis()}.$ext"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading image URI: $uri", e)
            null
        }
    }

    fun copyToClipboard(context: Context, payload: SharePayload) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            when (payload) {
                is SharePayload.Text -> {
                    val clip = ClipData.newPlainText("DSH Share", payload.content)
                    clipboard.setPrimaryClip(clip)
                }
                is SharePayload.Image -> {
                    // Stage text description for clipboard fallback
                    val clip = ClipData.newPlainText("DSH Shared Image", "[Image: ${payload.filename}]")
                    clipboard.setPrimaryClip(clip)
                }
                is SharePayload.MultipleImages -> {
                    val clip = ClipData.newPlainText("DSH Shared Images", "[${payload.images.size} Images Shared]")
                    clipboard.setPrimaryClip(clip)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy share payload to system clipboard", e)
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableExtraCompat(intent: Intent, key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                intent.getParcelableExtra(key, T::class.java)
            } catch (e: NoSuchMethodError) {
                intent.getParcelableExtra(key) as? T
            }
        } else {
            intent.getParcelableExtra(key) as? T
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> getParcelableArrayListExtraCompat(intent: Intent, key: String): java.util.ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                intent.getParcelableArrayListExtra(key, T::class.java)
            } catch (e: NoSuchMethodError) {
                intent.getParcelableArrayListExtra(key)
            }
        } else {
            intent.getParcelableArrayListExtra(key)
        }
    }
}
