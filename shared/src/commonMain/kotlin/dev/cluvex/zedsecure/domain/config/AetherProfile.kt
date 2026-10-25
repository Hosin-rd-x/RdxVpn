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
)
