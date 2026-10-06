package com.example.dshclient.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewHelperTest {

    @Test
    fun testBuildUrlWithToken() {
        val token = "test_token_123"
        val expectedUrl = "http://127.0.0.1:3080/?token=test_token_123"
        val actualUrl = WebViewHelper.buildUrl(token)
        assertEquals(expectedUrl, actualUrl)
    }

    @Test
    fun testBuildJsInjectionContainsCssAndStyleCreation() {
        val js = WebViewHelper.buildJsInjection()
        assertTrue(js.contains("document.createElement('style')"))
        assertTrue(js.contains("div[class*=\"_frame\"] { height: 100% !important;"))
        assertTrue(js.contains("#root { position: fixed !important;"))
        assertTrue(js.contains("div[class*=\"_dialog\"][aria-label*=\"Workspace\"]"))
        assertTrue(js.contains("document.head.appendChild(style)"))
    }

    @Test
    fun testBuildJsInjectionEscapesQuotesAndNewlines() {
        val customCss = "div[data-name='test'] {\n  content: '\\';\n}"
        val js = WebViewHelper.buildJsInjection(customCss)
        assertTrue(js.contains("div[data-name=\\'test\\']"))
        assertTrue(js.contains("\\n"))
        assertTrue(!js.contains("'\n"))
        assertTrue(js.contains("document.createElement('style')"))
    }
}
