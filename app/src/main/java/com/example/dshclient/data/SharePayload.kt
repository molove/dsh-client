package com.example.dshclient.data

/**
 * Normalized content shared to Remote DSH Web via Android's Share Sheet.
 */
sealed interface SharePayload {
    data class Text(val content: String) : SharePayload
    data class Image(
        val mimeType: String,
        val base64Data: String,
        val filename: String = "shared_image.jpg"
    ) : SharePayload
    data class MultipleImages(
        val images: List<Image>
    ) : SharePayload
}
