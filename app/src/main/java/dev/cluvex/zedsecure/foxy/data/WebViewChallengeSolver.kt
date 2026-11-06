package dev.cluvex.zedsecure.foxy.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "WebViewChallengeSolver"

/**
 * Solves a Fastly bot challenge with a real browser engine (WebView) instead of
 * plain HTTP calls.
 *
 * Why this exists: on some networks Fastly rejects plain HTTP clients (their
 * challenge-script fetch is refused), so the HTTP-only solver can never obtain
 * valid challenge cookies and every FxA call keeps failing with HTTP 406. A
 * real browser runs the challenge script and receives valid cookies; copying
 * those cookies into the control-plane jar lets the FxA calls through.
 *
 * The WebView is headless (never shown). It loads the Firefox-Accounts page,
 * waits until the challenge markers disappear from the document, then the
 * cookies are synced into [cookieJar].
 *
 * DIAGNOSTIC BUILD: every step is logged (never cookie values, never tokens)
 * so a device log shows exactly where the solve stops.
 */
object WebViewChallengeSolver {

    private const val CHALLENGE_PAGE = "https://accounts.firefox.com/"
    private const val SOLVE_TIMEOUT_MS = 60_000L
    private const val COOKIE_WAIT_MS = 5_000L
    private const val POLL_MS = 1_000L

    private val SYNC_HOSTS = listOf(
        "https://accounts.firefox.com",
        "https://api.accounts.firefox.com",
        "https://oauth.accounts.firefox.com",
        "https://vpn.mozilla.org",
    )

    /**
     * Solves the challenge on [pageUrl] (default [CHALLENGE_PAGE]). Pass the
     * root of the host whose request just got 406: the clearance is bound to
     * the host that was solved, so solving accounts while the failing request
     * goes to oauth left oauth unsolved. The cookies are synced into the
     * shared jar for every FxA host afterwards (see [SimpleCookieJar]).
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveAndSync(
        context: Context,
        cookieJar: SimpleCookieJar,
        pageUrl: String = CHALLENGE_PAGE,
    ): Boolean {
        AppLogger.i(TAG, "WebView solve started (page=$pageUrl)")
        return withTimeoutOrNull(SOLVE_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                var view: WebView? = null
                try {
                    val appContext = context.applicationContext
                    val manager = CookieManager.getInstance()
                    manager.setAcceptCookie(true)
                    AppLogger.i(TAG, "creating headless WebView")
                    val wv = WebView(appContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Stock browser UA on purpose: a genuine browser fingerprint is the point.
                        manager.setAcceptThirdPartyCookies(this, true)
                        // A WebViewClient is required, otherwise loadUrl opens the browser app.
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                AppLogger.i(TAG, "WebView page started: $url")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                AppLogger.i(TAG, "WebView page finished: $url")
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                super.onReceivedError(view, request, error)
                                AppLogger.w(
                                    TAG,
                                    "WebView page error: url=${request?.url} code=${error?.errorCode} " +
                                        "desc=${error?.description}",
                                )
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                AppLogger.w(
                                    TAG,
                                    "WebView HTTP error: url=${request?.url} " +
                                        "status=${errorResponse?.statusCode}",
                                )
                            }
                        }
                    }
                    view = wv
                    AppLogger.i(TAG, "WebView created, loading $pageUrl")
                    wv.loadUrl(pageUrl)
                    // Phase 1: wait until the challenge markers are gone from the document.
                    var markersGone = false
                    var waited = 0L
                    while (waited < SOLVE_TIMEOUT_MS) {
                        delay(POLL_MS)
                        waited += POLL_MS
                        val check = evalChallengeGone(wv)
                        AppLogger.i(TAG, "poll ${waited / 1000}s: markersGone=$check")
                        if (check == true) {
                            markersGone = true
                            break
                        }
                    }
                    AppLogger.i(TAG, "phase 1 done: markersGone=$markersGone after ${waited / 1000}s")
                    if (!markersGone) return@withContext false
                    // Phase 2: give the cookies a moment to settle, then sync them.
                    var synced = 0
                    var cookieWaited = 0L
                    while (cookieWaited < COOKIE_WAIT_MS) {
                        runCatching { manager.flush() }
                        val counts = SYNC_HOSTS.associateWith { host ->
                            runCatching { manager.getCookie(host) }.getOrNull()
                                ?.takeIf { it.isNotBlank() }?.split(";")?.size ?: 0
                        }
                        AppLogger.i(TAG, "cookie check: $counts")
                        synced = cookieJar.syncFromWebView()
                        AppLogger.i(TAG, "synced $synced cookies into control-plane jar")
                        if (synced > 0) break
                        delay(POLL_MS)
                        cookieWaited += POLL_MS
                    }
                    AppLogger.i(TAG, "WebView solve finished: synced=$synced")
                    synced > 0
                } catch (e: Exception) {
                    AppLogger.w(TAG, "headless challenge solve failed", e)
                    false
                } finally {
                    runCatching { view?.stopLoading() }
                    runCatching { view?.destroy() }
                    AppLogger.i(TAG, "WebView destroyed")
                }
            }
        } ?: run {
            AppLogger.w(TAG, "WebView solve timed out after ${SOLVE_TIMEOUT_MS / 1000}s")
            false
        }
    }

    /** True when the loaded document no longer contains Fastly challenge markers. Null when the check itself failed (page not ready). */
    private suspend fun evalChallengeGone(webView: WebView): Boolean? =
        suspendCancellableCoroutine { cont ->
            runCatching {
                webView.evaluateJavascript(
                    "(function(){var h=document.documentElement.outerHTML||'';" +
                        "return (h.indexOf('_fs-ch-')===-1 && h.indexOf('Client Challenge')===-1);})();"
                ) { result ->
                    if (cont.isActive) cont.resume(result?.trim() == "true")
                }
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
}
