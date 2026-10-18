package dev.cluvex.zedsecure.data.net

import dev.cluvex.zedsecure.core.VpnManager
import dev.cluvex.zedsecure.platform.httpGetViaSocks

/**
 * Fetches the exit IP's hygiene report from ipok.io.
 *
 * Always a *self* lookup: `/api/ip` with no `ip=` argument, so the site reads
 * whatever address the request arrives from — the tunnel's exit while connected
 * (the request rides the SOCKS port), and the phone's own carrier IP otherwise.
 * That is a truthful answer to "how is this connection seen" rather than a query
 * about some fixed server address.
 *
 * Quota: the free tier allows roughly 20 self lookups per source IP per day
 * (UTC reset), which is why this runs only when the details panel opens —
 * never on a timer.
 */
class IpHealthRepository(
    private val get: suspend (url: String) -> String = { url -> tunnelGet(url) },
) {
    suspend fun fetch(): IpHealth =
        runCatching { IpHealthPayload.parse(get(SELF_URL)) }
            .getOrElse { IpHealth(error = it.message?.take(120) ?: "unreachable") }

    private companion object {
        const val SELF_URL = "https://ipok.io/api/ip"

        private const val TIMEOUT_MS = 8_000

        private suspend fun tunnelGet(url: String): String = httpGetViaSocks(
            url,
            socksPort = VpnManager.activeSocksPort,
            userAgent = "RdxVpn",
            connectTimeoutMs = TIMEOUT_MS,
            readTimeoutMs = TIMEOUT_MS,
            closeConnection = true,
        )
    }
}
