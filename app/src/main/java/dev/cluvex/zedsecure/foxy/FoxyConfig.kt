package dev.cluvex.zedsecure.foxy

import dev.cluvex.zedsecure.foxy.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Remote configuration for the embedded Firefox-VPN account.
 *
 * Hossein rotates the embedded FxA account by editing `foxy-config.json` in the
 * PRIVATE backup repo (Hosin-rd-x/hermes-data-backup, main branch). The app tries to
 * fetch it at start; if the fetch fails (private raw URL, offline, ...) it falls back
 * to the token obfuscated in [FoxyCredentials].
 */
object FoxyConfig {

    private const val TAG = "FoxyConfig"

    // Token-free public raw URL reference; a private repo's raw endpoint needs auth,
    // so this is best-effort only.
    private const val REMOTE_CONFIG_URL =
        "https://gist.githubusercontent.com/Hosin-rd-x/25cb91654c5bd19d4d23134dd2ba4c24/raw/foxy-config.json"

    @Volatile
    private var cachedTokens: List<String>? = null

    fun invalidateCache() {
        cachedTokens = null
    }

    /**
     * Returns the full pool of refresh tokens configured remotely or embedded.
     */
    suspend fun refreshTokens(client: OkHttpClient = defaultClient()): List<String> {
        cachedTokens?.let { if (it.isNotEmpty()) return it }
        val remoteList = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(REMOTE_CONFIG_URL)
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = JSONObject(response.body?.string().orEmpty())
                    val list = mutableListOf<String>()

                    val arr = body.optJSONArray("refresh_tokens") ?: body.optJSONArray("tokens")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val t = arr.optString(i, "").trim()
                            if (t.isNotBlank() && !list.contains(t)) list.add(t)
                        }
                    }

                    listOf("refresh_token", "foxy_refresh_token").forEach { key ->
                        val single = body.optString(key, "").trim()
                        if (single.isNotBlank() && !list.contains(single)) list.add(single)
                    }
                    list
                }
            }.onFailure {
                AppLogger.d(TAG, "remote foxy-config unavailable (${it.message}); using embedded token")
            }.getOrNull()
        }

        val embedded = FoxyCredentials.refreshToken().trim()
        val combined = (remoteList.orEmpty() + listOf(embedded)).filter { it.isNotBlank() }.distinct()
        val result = if (combined.isNotEmpty()) combined else listOf(embedded)
        if (remoteList != null && remoteList.isNotEmpty()) {
            cachedTokens = result
        }
        return result
    }

    /**
     * Returns the primary refresh token (first available in pool).
     */
    suspend fun refreshToken(client: OkHttpClient = defaultClient()): String {
        return refreshTokens(client).firstOrNull() ?: FoxyCredentials.refreshToken()
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
}
