package dev.cluvex.zedsecure.data.config

/**
 * One-tap server set: fixed subscription links whose *contents* are
 * refreshed remotely, so a device never needs a new link (or a new APK)
 * to get fresh configs. Safe to re-add: entries are refreshed, not
 * duplicated.
 */
object PresetSubscriptions {
    val URLS = listOf(
        "https://gist.githubusercontent.com/Hosin-rd-x/25cb91654c5bd19d4d23134dd2ba4c24/raw/sub.txt",
        "https://gist.githubusercontent.com/Hosin-rd-x/25cb91654c5bd19d4d23134dd2ba4c24/raw/sub.json",
    )
}
