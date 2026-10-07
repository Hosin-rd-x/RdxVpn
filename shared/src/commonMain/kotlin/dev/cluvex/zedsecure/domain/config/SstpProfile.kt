package dev.cluvex.zedsecure.domain.config

import kotlinx.serialization.Serializable

/**
 * SSTP (Microsoft SSTP, RFC 4478) connection details: one HTTPS-wrapped PPP
 * session per profile. Servers only accept PAP/MS-CHAPv2, so each profile
 * carries the credentials of exactly one user.
 */
@Serializable
data class SstpProfile(
    val server: String,
    val port: Int = 443,
    val username: String = "",
    val password: String = "",
    val sni: String = "",
)
