package dev.cluvex.zedsecure.data.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Display markers fetched from Hossein's own server
 * (`https://shop.hosinrdx.ir/rdx-markers.json`), so a marker next to a
 * config name can change without an app update. The file only carries what
 * the app cannot know on its own: a mark for a whole subscription (the
 * Cloudflare list gets a cloud) and per-config overrides keyed by the
 * exact config name. Flags that live in a subscription's own content
 * (the own-server and SSTP lists carry flag emoji in their remarks) are
 * not repeated here.
 *
 * Unknown fields are ignored so the file can grow without an app update.
 * A missing [version] means 1; a higher unknown version is rejected by
 * [parseRemoteMarkers] so an old build never applies a shape it does not
 * understand.
 */
@Serializable
data class RemoteMarkers(
    val version: Int = 1,
    val subscriptions: Map<String, String> = emptyMap(),
    val profiles: Map<String, String> = emptyMap(),
) {
    /**
     * The marker to show before [profileName], or null for none.
     * Precedence: a profile with a known [countryCode] shows the FlagBadge
     * instead, so it never gets marker text; then an exact per-profile
     * override; then the marker of the profile's subscription, matched by
     * URL case-insensitively.
     */
    fun resolveMarker(
        profileName: String,
        countryCode: String?,
        subscriptionUrl: String?,
    ): String? {
        if (countryCode != null) return null
        profiles[profileName]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val url = subscriptionUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return subscriptions.entries
            .firstOrNull { it.key.equals(url, ignoreCase = true) }
            ?.value?.trim()?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** Highest file version this build understands. */
        const val SUPPORTED_VERSION = 1

        /** Parses the remote file, or null when it is unusable (bad JSON, a body far too large, or an unknown version). Callers keep their last-good markers then. */
        fun parse(raw: String): RemoteMarkers? {
            if (raw.isBlank() || raw.length > MAX_MARKERS_BYTES) return null
            val parsed = runCatching {
                Json { ignoreUnknownKeys = true }.decodeFromString<RemoteMarkers>(raw)
            }.getOrNull() ?: return null
            if (parsed.version > SUPPORTED_VERSION) return null
            return parsed
        }

        private const val MAX_MARKERS_BYTES = 64_000
    }
}
