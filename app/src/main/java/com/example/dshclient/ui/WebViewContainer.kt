package com.example.dshclient.ui

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

object WebViewHelper {
    private const val TAG = "WebViewContainer"
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 3080
    const val CSS_INJECTION = "html, body { width: 100% !important; height: 100% !important; margin: 0 !important; padding: 0 !important; overflow: hidden !important; } " +
            "#root { position: fixed !important; top: 0 !important; bottom: 0 !important; left: 0 !important; right: 0 !important; width: 100% !important; height: 100% !important; } " +
            "div[class*=\"_frame\"] { height: 100% !important; min-height: 100% !important; } " +
            "div[class*=\"_panel\"]:not([data-sidebar-right-panel]):not([class*=\"P3OORG\"]) { " +
            "height: min(720px, calc(100% - 32px)) !important; max-height: calc(100% - 32px) !important; " +
            "width: min(800px, calc(100% - 24px)) !important; max-width: calc(100% - 24px) !important; min-height: 260px !important; } " +
            "[role=\"dialog\"]:not(:has(nav)):not(:has(div[class*=\"_editorScope\"])):not([data-shortcut-modal]) { " +
            "height: auto !important; min-height: auto !important; max-height: calc(100% - 32px) !important; " +
            "width: max-content !important; min-width: min(280px, calc(100vw - 24px)) !important; max-width: min(440px, calc(100vw - 24px)) !important; } " +
            "[role=\"dialog\"] div[class*=\"_title\"], [role=\"dialog\"] div[class*=\"_header\"] { padding-right: 32px !important; } " +
            ".dsh-mobile-close-btn { position: absolute !important; top: 8px !important; right: 8px !important; width: 28px !important; height: 28px !important; " +
            "border-radius: 50% !important; border: none !important; background: rgba(128, 128, 128, 0.15) !important; color: currentColor !important; " +
            "display: flex !important; align-items: center !important; justify-content: center !important; cursor: pointer !important; z-index: 1200 !important; " +
            "padding: 0 !important; margin: 0 !important; opacity: 0.75 !important; -webkit-tap-highlight-color: transparent !important; } " +
            ".dsh-mobile-close-btn:active { opacity: 1 !important; background: rgba(128, 128, 128, 0.3) !important; } " +
            "div[class*=\"_dialog\"][aria-label*=\"Workspace\"], div[class*=\"_dialog\"]:has(div[class*=\"_editorScope\"]), div[class*=\"_dialog\"]:has(div[class*=\"_millerRow\"]) { " +
            "height: min(520px, calc(100% - 32px)) !important; max-height: calc(100% - 32px) !important; " +
            "min-height: 280px !important; width: min(680px, calc(100% - 24px)) !important; max-width: calc(100% - 24px) !important; } " +
            "div[class*=\"_editorScope\"] { height: 100% !important; min-height: 0 !important; display: flex !important; flex-direction: column !important; } " +
            "div[class*=\"_millerRow\"] { flex: 1 1 auto !important; min-height: 0 !important; overflow-y: auto !important; } " +
            "[role=\"dialog\"] > nav, div[class*=\"_panel\"] nav[class*=\"_nav\"] { " +
            "display: flex !important; width: 54px !important; min-width: 54px !important; max-width: 54px !important; " +
            "flex: 0 0 54px !important; flex-shrink: 0 !important; min-height: 0 !important; max-height: 100% !important; " +
            "overflow-y: auto !important; box-sizing: border-box !important; padding: 14px 6px 0 !important; gap: 8px !important; } " +
            "[role=\"dialog\"]:has([data-dsh-market-root]) > div { flex: 1 1 auto !important; width: auto !important; min-width: 0 !important; } " +
            "div[class*=\"_panel\"] div[class*=\"_navList\"] { min-height: 0 !important; overflow-y: auto !important; flex: 1 1 auto !important; } " +
            "div[class*=\"_panel\"] span[class*=\"_navLabel\"], div[class*=\"_panel\"] div[class*=\"_navTitle\"] { display: none !important; } " +
            "div[class*=\"_panel\"] button[class*=\"_navCell\"] { width: 42px !important; height: 42px !important; padding: 8px !important; justify-content: center !important; gap: 0 !important; } " +
            "div[class*=\"_panel\"] div[class*=\"_content\"] { min-width: 0 !important; overflow-x: hidden !important; } " +
            "div[class*=\"_panel\"] div[class*=\"_options\"] { overflow-y: auto !important; min-height: 0 !important; padding: 0 12px 16px !important; } " +
            "div[data-step-process-body], div[class*=\"_body\"][data-step-process-body], .O_Ebla_body { max-height: 400px !important; } " +
            "div[class*=\"_menu\"], [role=\"menu\"], div[class*=\"_list_4ub78\"], div[class*=\"_submenu_4ub78\"], div[class*=\"_scrollable_4ub78\"] { " +
            "max-height: min(380px, calc(100% - 48px)) !important; height: auto !important; min-height: auto !important; } " +
            "div[class*=\"paneEmptyCards\"], div[class*=\"_paneEmptyCards\"] { " +
            "overflow-y: auto !important; overflow-x: hidden !important; -webkit-overflow-scrolling: touch !important; " +
            "overscroll-behavior: contain !important; min-height: 0 !important; padding-bottom: 24px !important; } " +
            "@media (orientation: portrait), (max-width: 599px) { " +
            "div[data-sidebar-right-panel][data-sidebar-right-open=\"true\"] { " +
            "position: fixed !important; top: 0 !important; bottom: 0 !important; left: 0 !important; right: 0 !important; " +
            "width: 100vw !important; max-width: 100vw !important; height: 100% !important; max-height: 100% !important; " +
            "transform: none !important; z-index: 1000 !important; background: var(--dsw-alias-bg-base, #1e1e1e) !important; } " +
            "div[data-sidebar-right-panel]:not([data-sidebar-right-open=\"true\"]) { display: none !important; } " +
            "} " +
            "@media (orientation: landscape) and (min-width: 600px) { " +
            "div[class*=\"_panel\"]:not([data-sidebar-right-panel]):not([class*=\"P3OORG\"]) { height: calc(100% - 8px) !important; max-height: calc(100% - 8px) !important; } " +
            "div[class*=\"_dialog\"][aria-label*=\"Workspace\"], div[class*=\"_dialog\"]:has(div[class*=\"_editorScope\"]) { height: calc(100% - 16px) !important; max-height: calc(100% - 16px) !important; } " +
            "[role=\"dialog\"] > nav, div[class*=\"_panel\"] nav[class*=\"_nav\"] { width: 188px !important; min-width: 188px !important; max-width: 188px !important; flex: 0 0 188px !important; padding: 22px 12px 0 !important; gap: 18px !important; } " +
            "div[class*=\"_panel\"] span[class*=\"_navLabel\"], div[class*=\"_panel\"] div[class*=\"_navTitle\"] { display: block !important; } " +
            "div[class*=\"_panel\"] button[class*=\"_navCell\"] { width: 100% !important; height: 40px !important; padding: 9px 16px 9px 12px !important; justify-content: flex-start !important; gap: 8px !important; } " +
            "div[class*=\"_panel\"] div[class*=\"_options\"] div[class*=\"_sub\"] { display: none !important; } " +
            "div[class*=\"_panel\"] div[class*=\"_options\"] div[class*=\"_stickyHead\"] { position: static !important; } " +
            "div[class*=\"_panel\"] div[class*=\"_options\"] div[class*=\"_root\"] { height: auto !important; min-height: 100% !important; flex: none !important; } " +
            "div[class*=\"_panel\"] div[class*=\"_options\"] div[class*=\"_body\"] { height: auto !important; min-height: 350px !important; flex: none !important; overflow: visible !important; } " +
            "div[data-step-process-body], div[class*=\"_body\"][data-step-process-body], .O_Ebla_body { max-height: 260px !important; } " +
            "div[class*=\"_menu\"], [role=\"menu\"], div[class*=\"_list_4ub78\"], div[class*=\"_submenu_4ub78\"], div[class*=\"_scrollable_4ub78\"] { max-height: calc(100% - 24px) !important; } " +
            "div[class*=\"_frame\"]:has(div[data-sidebar-right-open=\"true\"]) { " +
            "grid-template-columns: 56px minmax(0px, 1fr) min(320px, 40vw) !important; } " +
            "div[class*=\"_rightbarCol\"]:has(div[data-sidebar-right-open=\"true\"]) { " +
            "width: 100% !important; height: 100% !important; position: relative !important; overflow: hidden !important; } " +
            "div[data-sidebar-right-panel][data-sidebar-right-open=\"true\"] { " +
            "position: relative !important; top: 0 !important; bottom: 0 !important; left: 0 !important; right: 0 !important; " +
            "width: 100% !important; max-width: 100% !important; height: 100% !important; max-height: 100% !important; " +
            "transform: none !important; box-shadow: none !important; z-index: 10 !important; background: var(--dsw-alias-bg-base, #1e1e1e) !important; } " +
            "}"

    fun buildUrl(token: String, host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT): String {
        return "http://$host:$port/?token=$token"
    }

    fun buildJsInjection(css: String = CSS_INJECTION): String {
        val escapedCss = css
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", "")
            .replace("\n", "\\n")
        return """
            (function() {
                var style = document.getElementById('dsh-mobile-style') || document.createElement('style');
                style.id = 'dsh-mobile-style';
                style.innerHTML = '$escapedCss';
                if (!style.parentNode && document.head) {
                    document.head.appendChild(style);
                }

                function attachCloseButtons() {
                    var dialogs = document.querySelectorAll('[role="dialog"]');
                    for (var i = 0; i < dialogs.length; i++) {
                        var dialog = dialogs[i];

                        // Skip settings or workspace modals with navigation or editors
                        if (dialog.querySelector('nav') || dialog.querySelector('div[class*="_editorScope"]')) {
                            continue;
                        }

                        // Compact height override for popovers/stat panels so they do not stretch to 720px
                        dialog.style.setProperty('height', 'auto', 'important');
                        dialog.style.setProperty('min-height', 'auto', 'important');
                        dialog.style.setProperty('max-height', 'calc(100% - 32px)', 'important');

                        // If close button already exists, skip
                        if (dialog.querySelector('.dsh-mobile-close-btn') || dialog.querySelector('button[aria-label="Close"]')) {
                            continue;
                        }

                        var btn = document.createElement('button');
                        btn.type = 'button';
                        btn.className = 'dsh-mobile-close-btn';
                        btn.setAttribute('aria-label', 'Close');
                        btn.innerHTML = '<svg width="12" height="12" viewBox="0 0 12 12" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="2" y1="2" x2="10" y2="10"/><line x1="2" y1="10" x2="10" y2="2"/></svg>';

                        var dismiss = function(e) {
                            if (e) {
                                e.preventDefault();
                                e.stopPropagation();
                            }
                            document.dispatchEvent(new KeyboardEvent('keydown', {
                                key: 'Escape',
                                code: 'Escape',
                                keyCode: 27,
                                which: 27,
                                bubbles: true,
                                cancelable: true
                            }));
                        };

                        btn.addEventListener('click', dismiss);
                        btn.addEventListener('pointerup', dismiss);

                        dialog.appendChild(btn);
                    }
                }

                attachCloseButtons();

                if (!window._dshCloseBtnObserver && document.body) {
                    var observer = new MutationObserver(function() {
                        attachCloseButtons();
                    });
                    observer.observe(document.body, { childList: true, subtree: true });
                    window._dshCloseBtnObserver = observer;
                }
            })();
        """.trimIndent()
    }
}

/**
 * Renders the dsh-web interface inside an Android WebView, injecting mobile CSS,
 * handling auth cookies, and notifying the caller of the WebView instance for back navigation.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewContainer(
    url: String?,
    modifier: Modifier = Modifier,
    onWebViewCreated: ((WebView) -> Unit)? = null,
    onCanGoBackChanged: ((Boolean) -> Unit)? = null,
    onRelease: (() -> Unit)? = null
) {
    if (url.isNullOrBlank()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Waiting for connection to Q...",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val loadError = remember(url) { mutableStateOf(false) }

    if (loadError.value) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Cannot reach Q.\nIs the SSH tunnel active?",
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { loadError.value = false }) {
                    Text("Retry")
                }
            }
        }
        return
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                @Suppress("DEPRECATION")
                settings.databaseEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                WebView.setWebContentsDebuggingEnabled(true)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        Log.d("WebViewConsole", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                        return true
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        val js = WebViewHelper.buildJsInjection()
                        view?.evaluateJavascript(js, null)
                        onCanGoBackChanged?.invoke(view?.canGoBack() == true)
                    }

                    override fun doUpdateVisitedHistory(
                        view: WebView?,
                        url: String?,
                        isReload: Boolean
                    ) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        onCanGoBackChanged?.invoke(view?.canGoBack() == true)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            loadError.value = true
                        }
                    }
                }
                tag = url
                loadUrl(url)
                onWebViewCreated?.invoke(this)
                onCanGoBackChanged?.invoke(canGoBack())
            }
        },
        update = { webView ->
            onWebViewCreated?.invoke(webView)
            onCanGoBackChanged?.invoke(webView.canGoBack())
            if (webView.tag != url) {
                webView.tag = url
                webView.loadUrl(url)
            }
        },
        onRelease = { webView ->
            webView.destroy()
            onRelease?.invoke()
        }
    )
}
