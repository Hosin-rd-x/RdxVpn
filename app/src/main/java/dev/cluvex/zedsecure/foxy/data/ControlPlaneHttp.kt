package dev.cluvex.zedsecure.foxy.data

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

private const val TAG = "ControlPlaneHttp"

const val MOZILLA_VPN_USER_AGENT = "MozillaVPN/2.35.0 (sys:linux; iap:true)"

fun Request.Builder.applyMozillaVpnHeaders(): Request.Builder {
    header("User-Agent", MOZILLA_VPN_USER_AGENT)
    header("Accept", "application/json")
    return this
}

// FxA calls ONLY (used by FxaAuthRepository). Presents the request with the
// same Chrome desktop identity the Fastly challenge solver uses: Mozilla's
// gate treats a retry under a different identity than the one that solved
// the challenge as a fresh, unsolved client and answers 406 again. This is
// the combination the reference flow proves works (solve AND request on the
// same host, same UA). The Firefox-UA "Vauth parity" from 3.8.13 still got
// 406 even with a solved challenge, so it is gone. applyMozillaVpnHeaders
// above is left untouched so Guardian and the server-list client keep their
// exact old headers; nothing outside the Foxy module uses either helper.
const val CHROME_DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

fun Request.Builder.applyChromeHeaders(): Request.Builder {
    header("User-Agent", CHROME_DESKTOP_USER_AGENT)
    header("Accept", "*/*")
    return this
}

class SimpleCookieJar : CookieJar {
    private val byDomain = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            val bucket = byDomain.getOrPut(cookie.domain) { mutableListOf() }
            synchronized(bucket) {
                bucket.removeAll { it.name == cookie.name }
                bucket.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<Cookie>()
        for ((domain, cookies) in byDomain) {
            if (url.host == domain || url.host.endsWith(".$domain")) {
                synchronized(cookies) {
                    result.addAll(cookies.filter { it.expiresAt > now })
                }
            }
        }
        return result
    }

    fun set(name: String, value: String, domain: String) {
        val bucket = byDomain.getOrPut(domain) { mutableListOf() }
        synchronized(bucket) {
            bucket.removeAll { it.name == name }
            bucket.add(
                Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(domain)
                    .path("/")
                    .expiresAt(Long.MAX_VALUE)
                    .build(),
            )
        }
    }

    fun snapshotAll(): List<Cookie> = byDomain.values.flatten()

    /**
     * Copies cookies a real browser (WebView) received into this jar. A browser
     * passes the Fastly bot challenge where plain HTTP calls cannot, so its
     * cookies are what lets the FxA API calls through. Returns how many cookies
     * were copied. Never throws.
     */
    fun syncFromWebView(): Int {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return 0
        var count = 0
        for (urlStr in listOf(
            "https://accounts.firefox.com",
            "https://api.accounts.firefox.com",
            "https://oauth.accounts.firefox.com",
            "https://vpn.mozilla.org",
        )) {
            val header = runCatching { manager.getCookie(urlStr) }.getOrNull() ?: continue
            for (raw in header.split(";")) {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) continue
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isEmpty() || value.isEmpty()) continue
                // Stored under firefox.com so it matches every FxA subdomain,
                // the same way the challenge solver's own cookies are stored.
                set(name, value, "firefox.com")
                count++
            }
        }
        return count
    }
}

private class ProtectingSocketFactory : SocketFactory() {

    private fun protect(socket: Socket, localAddress: InetSocketAddress?): Socket {

        runCatching {
            if (!socket.isBound) socket.bind(localAddress ?: InetSocketAddress(0))
        }.onFailure {
            AppLogger.w(TAG, "could not bind a control-plane socket before protecting it", it)
        }
        val protector = ControlPlaneHttp.socketProtector
        if (protector != null) {
            val protectedOk = runCatching { protector.invoke(socket) }.getOrDefault(false)
            if (!protectedOk) {
                AppLogger.w(
                    TAG,
                    "VpnService.protect() refused a control-plane socket; this request would be routed " +
                        "back into the tunnel instead of out to the network",
                )
            }
        }
        return socket
    }

    override fun createSocket(): Socket = protect(Socket(), null)

    override fun createSocket(host: String, port: Int): Socket =
        protect(Socket(), null).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, localAddr: InetAddress, localPort: Int): Socket =
        protect(Socket(), InetSocketAddress(localAddr, localPort))
            .apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        protect(Socket(), null).apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int, localAddr: InetAddress, localPort: Int): Socket =
        protect(Socket(), InetSocketAddress(localAddr, localPort))
            .apply { connect(InetSocketAddress(host, port)) }
}

object ControlPlaneHttp {
    val cookieJar = SimpleCookieJar()

    @Volatile
    var socketProtector: ((Socket) -> Boolean)? = null

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // Vauth parity: the control-plane client speaks HTTP/1.1 only, like
            // a plain browser stack. This client is used solely inside the Foxy
            // module (FxA, Guardian, server list); no other engine (Aether,
            // Xray, SSTP, ...) ever touches it.
            .protocols(listOf(Protocol.HTTP_1_1))
            .cookieJar(cookieJar)
            .socketFactory(ProtectingSocketFactory())
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
