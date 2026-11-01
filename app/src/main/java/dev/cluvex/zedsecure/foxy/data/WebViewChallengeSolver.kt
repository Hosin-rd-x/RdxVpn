package dev.cluvex.zedsecure.foxy.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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
 * cookies are synced into [cookieJar]. Returns true when at least one cookie
 * was synced.
 */
object WebViewChallengeSolver {

    private const val CHALLENGE_PAGE = "https://accounts.firefox.com/"
    private const val SOLVE_TIMEOUT_MS = 60_000L
    private const val COOKIE_WAIT_MS = 5_000L
    private const val POLL_MS = 1_000L

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solveAndSync(context: Context, cookieJar: SimpleCookieJar): Boolean {
        return withTimeoutOrNull(SOLVE_TIMEOUT_MS) {
            withContext(Dispatchers.Main) {
                var view: WebView? = null
                try {
                    val appContext = context.applicationContext
                    val manager = CookieManager.getInstance()
                    manager.setAcceptCookie(true)
                    val wv = WebView(appContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // Stock browser UA on purpose: a genuine browser fingerprint is the point.
                        manager.setAcceptThirdPartyCookies(this, true)
                        // A WebViewClient is required, otherwise loadUrl opens the browser app.
                        webViewClient = WebViewClient()
                    }
                    view = wv
                    wv.loadUrl(CHALLENGE_PAGE)
                    // Phase 1: wait until the challenge markers are gone from the document.
                    var markersGone = false
                    var waited = 0L
                    while (waited < SOLVE_TIMEOUT_MS) {
                        delay(POLL_MS)
                        waited += POLL_MS
                        if (evalChallengeGone(wv) == true) {
                            markersGone = true
                            break
                        }
                    }
                    if (!markersGone) return@withContext false
                    // Phase 2: give the cookies a moment to settle, then sync them.
                    var synced = 0
                    var cookieWaited = 0L
                    while (cookieWaited < COOKIE_WAIT_MS) {
                        runCatching { manager.flush() }
                        synced = cookieJar.syncFromWebView()
                        if (synced > 0) break
                        delay(POLL_MS)
                        cookieWaited += POLL_MS
                    }
                    synced > 0
                } catch (e: Exception) {
                    AppLogger.w("WebViewChallengeSolver", "headless challenge solve failed", e)
                    false
                } finally {
                    runCatching { view?.stopLoading() }
                    runCatching { view?.destroy() }
                }
            }
        } ?: false
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
