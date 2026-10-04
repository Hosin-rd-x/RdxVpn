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
        "https://raw.githubusercontent.com/Hosin-rd-x/hermes-data-backup/main/foxy-config.json"

    @Volatile
    private var cachedToken: String? = null

    /**
     * Returns the refresh token to sign in with: the remotely configured one when the
     * config file is reachable and carries a token, otherwise the embedded one.
     */
    suspend fun refreshToken(client: OkHttpClient = defaultClient()): String {
        cachedToken?.let { if (it.isNotBlank()) return it }
        val remote = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(REMOTE_CONFIG_URL).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = JSONObject(response.body?.string().orEmpty())
                    listOf("refresh_token", "foxy_refresh_token")
                        .asSequence()
                        .map { key -> body.optString(key) }
                        .firstOrNull { it.isNotBlank() }
                }
            }.onFailure {
                AppLogger.d(TAG, "remote foxy-config unavailable (${it.message}); using the embedded token")
            }.getOrNull()
        }
        val token = remote ?: FoxyCredentials.refreshToken()
        if (remote != null) cachedToken = token
        return token
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
}
