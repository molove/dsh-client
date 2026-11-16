package com.example.dshclient.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(js.contains("div[data-step-process-body]"))
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

    @Test
    fun testBuildJsInjectionContainsCloseButtonLogic() {
        val js = WebViewHelper.buildJsInjection()
        assertTrue(js.contains(".dsh-mobile-close-btn"))
        assertTrue(js.contains("attachCloseButtons"))
        assertTrue(js.contains("MutationObserver"))
        assertTrue(js.contains("key: 'Escape'"))
        assertTrue(js.contains("code: 'Escape'"))
        assertTrue(js.contains("keyCode: 27"))
    }

    @Test
    fun testBuildShareInjectionJsForText() {
        val payload = com.example.dshclient.data.SharePayload.Text("Box 12: Christmas Decorations")
        val js = WebViewHelper.buildShareInjectionJs(payload)
        assertTrue(js.contains("Box 12: Christmas Decorations"))
        assertTrue(js.contains("document.querySelector('textarea, [contenteditable=\"true\"]')"))
        assertTrue(js.contains("Event('input'"))
        assertTrue(js.contains("Event('change'"))
    }

    @Test
    fun testBuildShareInjectionJsForImage() {
        val payload = com.example.dshclient.data.SharePayload.Image(
            mimeType = "image/jpeg",
            base64Data = "aGVsbG8=",
            filename = "declutter_1.jpg"
        )
        val js = WebViewHelper.buildShareInjectionJs(payload)
        assertTrue(js.contains("aGVsbG8="))
        assertTrue(js.contains("declutter_1.jpg"))
        assertTrue(js.contains("ClipboardEvent('paste'"))
        assertTrue(js.contains("base64ToBlob"))
        assertTrue(js.contains("dt.items.add"))
    }

    @Test
    fun testCssInjectionContainsCloseButtonAndDialogHeightRules() {
        assertTrue(WebViewHelper.CSS_INJECTION.contains(".dsh-mobile-close-btn"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("[role=\"dialog\"]"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("height: auto !important"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("padding-right: 32px !important"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("div[class*=\"_menu\"]"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("[role=\"menu\"]"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("div[data-sidebar-right-panel][data-sidebar-right-open=\"true\"]"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("grid-template-columns: 56px minmax(0px, 1fr) min(320px, 40vw)"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("div[class*=\"paneEmptyCards\"]"))
    }

    @Test
    fun testCssInjectionContainsSettingsModalRules() {
        assertTrue(WebViewHelper.CSS_INJECTION.contains("div[class*=\"VOzbGW_overlay\"]"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("div[class*=\"VOzbGW_panel\"]"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("div[class*=\"VOzbGW_navList\"]"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("button[class*=\"VOzbGW_navCell\"]"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("div[class*=\"VOzbGW_options\"]"))
        assertTrue(WebViewHelper.CSS_INJECTION.contains("span[class*=\"VOzbGW_navLabel\"]"))
    }

    @Test
    fun testCssInjectionDoesNotContainBroadOverlaySelector() {
        assertFalse(
            "CSS injection should not contain generic div[class*=\"_overlay\"] as it intercepts input/mic touches",
            WebViewHelper.CSS_INJECTION.contains("div[class*=\"_overlay\"]")
        )
    }
}
