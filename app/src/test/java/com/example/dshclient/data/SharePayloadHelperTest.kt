package com.example.dshclient.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream

class SharePayloadHelperTest {

    @Test
    fun returnsNullForNullIntent() {
        val context = mock(Context::class.java)
        val result = SharePayloadHelper.parseIntent(context, null)
        assertNull(result)
    }

    @Test
    fun returnsNullForNonShareAction() {
        val context = mock(Context::class.java)
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(Intent.ACTION_VIEW)

        val result = SharePayloadHelper.parseIntent(context, intent)
        assertNull(result)
    }

    @Test
    fun extractsPlainTextFromActionSend() {
        val context = mock(Context::class.java)
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(Intent.ACTION_SEND)
        `when`(intent.type).thenReturn("text/plain")
        `when`(intent.getStringExtra(Intent.EXTRA_TEXT)).thenReturn("Box 42: Vintage Cables")

        val result = SharePayloadHelper.parseIntent(context, intent)
        assertNotNull(result)
        assertTrue(result is SharePayload.Text)
        assertEquals("Box 42: Vintage Cables", (result as SharePayload.Text).content)
    }

    @Test
    fun extractsSingleImageFromActionSend() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)

        val intent = mock(Intent::class.java)
        val uri = mock(Uri::class.java)
        `when`(intent.action).thenReturn(Intent.ACTION_SEND)
        `when`(intent.type).thenReturn("image/jpeg")
        `when`(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).thenReturn(uri)
        `when`(resolver.getType(uri)).thenReturn("image/jpeg")

        val fakeImageData = "fake image bytes".toByteArray()
        `when`(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(fakeImageData))

        val result = SharePayloadHelper.parseIntent(context, intent)
        assertNotNull(result)
        assertTrue(result is SharePayload.Image)
        val img = result as SharePayload.Image
        assertEquals("image/jpeg", img.mimeType)
        assertTrue(img.base64Data.isNotEmpty())
    }

    @Test
    fun extractsMultipleImagesFromActionSendMultiple() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        `when`(context.contentResolver).thenReturn(resolver)

        val intent = mock(Intent::class.java)
        val uri1 = mock(Uri::class.java)
        val uri2 = mock(Uri::class.java)
        val uriList = arrayListOf(uri1, uri2)

        `when`(intent.action).thenReturn(Intent.ACTION_SEND_MULTIPLE)
        `when`(intent.type).thenReturn("image/*")
        `when`(intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).thenReturn(uriList)
        `when`(resolver.getType(uri1)).thenReturn("image/jpeg")
        `when`(resolver.getType(uri2)).thenReturn("image/png")

        `when`(resolver.openInputStream(uri1)).thenReturn(ByteArrayInputStream("img1".toByteArray()))
        `when`(resolver.openInputStream(uri2)).thenReturn(ByteArrayInputStream("img2".toByteArray()))

        val result = SharePayloadHelper.parseIntent(context, intent)
        assertNotNull(result)
        assertTrue(result is SharePayload.MultipleImages)
        val multi = result as SharePayload.MultipleImages
        assertEquals(2, multi.images.size)
        assertEquals("image/jpeg", multi.images[0].mimeType)
        assertEquals("image/png", multi.images[1].mimeType)
    }
}
