package dev.cluvex.zedsecure.domain.config

import kotlinx.serialization.Serializable

@Serializable
data class AetherProfile(
    val method: String,
    val args: List<String>,
    val displayName: String,
    val address: String = "",
    val ipVersion: String = "any",
    val fingerprint: String = "",
    val cipherSuites: String = "",
    val finalMask: String = "",
    val echConfigList: String = "",
    val alpn: String = "",
    /**
     * Gateway the probe verified for this profile (e.g.
     * "162.159.198.54:443"), captured from the probe run's own log.
     * Empty when the binary did not announce one: the service then
     * falls back to --quick-reconnect alone (still no fresh scan).
     * Defaulted so profiles saved before this field existed still load.
     */
    val pinnedPeer: String = "",
)
