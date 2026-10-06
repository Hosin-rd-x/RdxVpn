package dev.cluvex.zedsecure.data.config

/**
 * One-tap server set: fixed subscription links whose *contents* are
 * refreshed remotely, so a device never needs a new link (or a new APK)
 * to get fresh configs. Safe to re-add: entries are refreshed, not
 * duplicated.
 *
 * One URL only: every config (share links + converted xray JSON + pinned
 * BlueKnight) is merged server-side into sub.txt, so the button creates a
 * single subscription instead of a cluttered list.
 */
object PresetSubscriptions {
    val URLS = listOf(
        "https://gist.githubusercontent.com/Hosin-rd-x/25cb91654c5bd19d4d23134dd2ba4c24/raw/sub.txt",
    )

    /**
     * Old second subscription (the raw xray JSON slot). Its configs already
     * live inside URLS[0], so the preset button removes it after a
     * successful refresh instead of leaving a stale entry behind.
     */
    val LEGACY_URLS = listOf(
        "https://gist.githubusercontent.com/Hosin-rd-x/25cb91654c5bd19d4d23134dd2ba4c24/raw/sub.json",
    )
}
