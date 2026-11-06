package dev.cluvex.zedsecure.foxy.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.cluvex.zedsecure.foxy.data.model.RuntimeAuth

private const val TAG = "TokenStore"

class TokenStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "foxyvpn_tokens",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveAuth(auth: RuntimeAuth) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, auth.accessToken)
            .putString(KEY_REFRESH_TOKEN, auth.refreshToken)
            .putLong(KEY_EXPIRES_AT, auth.expiresAtEpochSeconds)
            .apply()
    }

    fun loadAuth(): RuntimeAuth? = runCatching {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        RuntimeAuth(access, refresh, expiresAt)
    }.onFailure {
        AppLogger.w(TAG, "could not read the stored session", it)
    }.getOrNull()

    fun hasValidAccessToken(): Boolean {
        val auth = loadAuth() ?: return false
        if (auth.expiresAtEpochSeconds <= 0L) return false
        val nowSeconds = System.currentTimeMillis() / 1000
        return auth.expiresAtEpochSeconds - nowSeconds > CLOCK_SKEW_TOLERANCE_SECONDS
    }

    /**
     * Like [loadAuth], but also returns a silently seeded session whose access token has
     * not been minted yet ([silentRestoreFromEmbedded] stores an empty access token on
     * purpose). The refresh grant must read the session through this function, otherwise
     * the half-finished seed looks like "Not signed in" and silent sign-in can never run.
     */
    fun loadSessionAllowingEmptyAccess(): RuntimeAuth? = runCatching {
        val access = prefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }.orEmpty()
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (access.isBlank() && refresh.isNullOrBlank()) return@runCatching null
        RuntimeAuth(access, refresh, expiresAt)
    }.onFailure {
        AppLogger.w(TAG, "could not read the stored session", it)
    }.getOrNull()

    fun hasStoredSession(): Boolean = loadAuth() != null

    fun hasRefreshToken(): Boolean = loadAuth()?.refreshToken != null

    @Deprecated(
        "Checks only access-token freshness; prefer hasValidAccessToken() or FxaAuthRepository.restoreSession().",
        ReplaceWith("hasValidAccessToken()"),
    )
    fun hasValidSession(): Boolean = hasValidAccessToken()

    /**
     * Seeds the store from the embedded/remote refresh token when no session exists yet
     * (silent sign-in). Returns true when a fresh embedded session was stored, or when
     * the stored session uses the same refresh token but has no usable access token
     * yet — in that case the caller must still run the refresh grant, so "true" here
     * really means "a mint attempt must follow". Returns false only when a usable
     * session already exists (nothing to seed, nothing to mint).
     *
     * The comparison must read through [loadSessionAllowingEmptyAccess]: with
     * [loadAuth] a seeded-but-unminted session (empty access token) was invisible,
     * so this always re-seeded and the "seed applied" log was meaningless. But the
     * plain swap alone would be worse — after one failed mint the next sign-in
     * would see the seeded session, return false, and skip the refresh entirely.
     */
    fun silentRestoreFromEmbedded(token: String): Boolean {
        if (token.isBlank()) return false
        val existing = runCatching { loadSessionAllowingEmptyAccess() }.getOrNull()
        if (existing != null && token == existing.refreshToken) {
            return !hasValidAccessToken()
        }
        saveAuth(
            RuntimeAuth(
                accessToken = "",
                refreshToken = token,
                expiresAtEpochSeconds = 0L,
            ),
        )
        return true
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"

        private const val CLOCK_SKEW_TOLERANCE_SECONDS = 60L
    }
}
