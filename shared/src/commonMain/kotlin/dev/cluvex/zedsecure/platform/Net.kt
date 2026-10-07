package dev.cluvex.zedsecure.platform

internal expect suspend fun httpGetViaSocks(
    url: String,
    socksPort: Int?,
    userAgent: String = "RdxVpn",
    connectTimeoutMs: Int = 8_000,
    readTimeoutMs: Int = 8_000,
    closeConnection: Boolean = false,
): String

internal class HttpTiming(
    val bytes: Long,
    val wallNanos: Long,
    val ttfbNanos: Long,
    val serverMillis: Double,
)

internal expect suspend fun httpTimedTransfer(
    url: String,
    socksPort: Int?,
    upload: Boolean,
    uploadBytes: Int,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
): HttpTiming

internal expect suspend fun resolveHostAddress(host: String): String?

internal expect suspend fun tcpConnectMillis(host: String, port: Int, timeoutMs: Int): Long

internal expect suspend fun tlsHandshakeMillis(host: String, port: Int, timeoutMs: Int): Long

/**
 * Speaks the real SSTP greeting: TCP connect, TLS handshake, then the
 * HTTP request the client sends before PPP starts, timed until the server
 * answers.
 *
 * @return elapsed ms, or -1 when the host does not answer as an SSTP server.
 */
internal expect suspend fun sstpProbeMillis(host: String, port: Int, timeoutMs: Int): Long

internal expect suspend fun httpStreamTransfer(
    url: String,
    socksPort: Int?,
    upload: Boolean,
    uploadBytes: Long,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    onChunk: (deltaBytes: Long) -> Boolean,
): HttpTiming
