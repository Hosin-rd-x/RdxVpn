package dev.cluvex.zedsecure.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext

internal actual suspend fun httpGetViaSocks(
    url: String,
    socksPort: Int?,
    userAgent: String,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    closeConnection: Boolean,
): String = withContext(Dispatchers.IO) {
    val connection = if (socksPort != null) {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort))
        URL(url).openConnection(proxy) as HttpURLConnection
    } else {
        URL(url).openConnection() as HttpURLConnection
    }
    connection.apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", userAgent)
        if (closeConnection) setRequestProperty("Connection", "close")
    }
    try {
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${connection.responseCode}")
        }
        connection.inputStream.bufferedReader().use { it.readText() }
    } catch (e: NullPointerException) {
        throw platformHttpFailure(e)
    } finally {
        connection.disconnectQuietly()
    }
}

internal actual suspend fun httpTimedTransfer(
    url: String,
    socksPort: Int?,
    upload: Boolean,
    uploadBytes: Int,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
): HttpTiming = withContext(Dispatchers.IO) {
    val connection = if (socksPort != null) {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort))
        URL(url).openConnection(proxy) as HttpURLConnection
    } else {
        URL(url).openConnection() as HttpURLConnection
    }
    connection.apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "ZedSecure-SpeedTest")
        setRequestProperty("Accept-Encoding", "identity")
    }
    var bytes = 0L
    val t0 = System.nanoTime()
    try {
        val buf = ByteArray(64 * 1024)
        if (upload) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(uploadBytes)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { os ->
                var remaining = uploadBytes
                while (remaining > 0) {
                    val n = minOf(buf.size, remaining)
                    os.write(buf, 0, n)
                    os.flush()
                    remaining -= n
                    bytes += n
                }
            }
        }
        val code = connection.responseCode
        val ttfb = System.nanoTime() - t0
        if (code !in 200..299) throw IllegalStateException("HTTP $code")

        connection.inputStream.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (!upload) bytes += n
            }
        }
        val wall = System.nanoTime() - t0
        val server = parseServerTimingMillis(connection.getHeaderField("server-timing"))
        HttpTiming(bytes = bytes, wallNanos = wall, ttfbNanos = ttfb, serverMillis = server)
    } catch (e: NullPointerException) {
        throw platformHttpFailure(e)
    } finally {
        connection.disconnectQuietly()
    }
}

private fun parseServerTimingMillis(header: String?): Double {
    if (header.isNullOrBlank()) return 0.0
    val idx = header.indexOf("dur=", ignoreCase = true)
    if (idx < 0) return 0.0
    val tail = header.substring(idx + 4)
    val num = tail.takeWhile { it.isDigit() || it == '.' }
    return num.toDoubleOrNull() ?: 0.0
}

internal actual suspend fun resolveHostAddress(host: String): String? = withContext(Dispatchers.IO) {
    runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
}

internal actual suspend fun tcpConnectMillis(host: String, port: Int, timeoutMs: Int): Long =
    withContext(Dispatchers.IO) {
        // Resolve outside the timed window: the system's DNS latency is not the relay's
        // latency, and on filtered networks the lookup alone can add seconds to the ping.
        val target = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return@withContext -1L
        }
        var socket: Socket? = null
        val start = System.currentTimeMillis()
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(target, port), timeoutMs)
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        } finally {
            runCatching { socket?.takeIf { !it.isClosed }?.close() }
        }
    }

internal actual suspend fun tlsHandshakeMillis(host: String, port: Int, timeoutMs: Int): Long =
    withContext(Dispatchers.IO) {
        var plain: Socket? = null
        var sslClose: SSLSocket? = null
        val start = System.currentTimeMillis()
        try {
            val base = Socket()
            plain = base
            base.connect(InetSocketAddress(host, port), timeoutMs)
            val ssl = SSLContext.getDefault().socketFactory.createSocket(base, host, port, true) as SSLSocket
            sslClose = ssl
            ssl.soTimeout = timeoutMs
            runCatching {
                ssl.sslParameters = ssl.sslParameters.apply { applicationProtocols = arrayOf("h2") }
            }
            ssl.startHandshake()
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            -1L
        } finally {
            runCatching { sslClose?.takeIf { !it.isClosed }?.close() }
            runCatching { plain?.takeIf { !it.isClosed }?.close() }
        }
    }

/**
 * The greeting Open-SSTP-Client sends right after TLS is up. The server
 * answering "200" is what tells an actual SSTP listener from any other
 * TLS port in the list.
 */
internal actual suspend fun sstpProbeMillis(host: String, port: Int, timeoutMs: Int): Long =
    withContext(Dispatchers.IO) {
        var plain: Socket? = null
        var sslClose: SSLSocket? = null
        val start = System.currentTimeMillis()
        try {
            val base = Socket()
            plain = base
            base.connect(InetSocketAddress(host, port), timeoutMs)
            val ssl = SSLContext.getDefault().socketFactory.createSocket(base, host, port, true) as SSLSocket
            sslClose = ssl
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()

            val request = (
                arrayOf(
                    "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1",
                    "Content-Length: 18446744073709551615",
                    "Host: $host",
                ).joinToString("\r\n", postfix = "\r\n\r\n")
                ).toByteArray(Charsets.US_ASCII)
            ssl.getOutputStream().apply {
                write(request)
                flush()
            }

            val reply = StringBuilder()
            val buf = ByteArray(256)
            while (System.currentTimeMillis() - start < timeoutMs && !reply.contains("\r\n\r\n")) {
                val n = ssl.getInputStream().read(buf)
                if (n < 0) break
                reply.append(String(buf, 0, n, Charsets.ISO_8859_1))
                if (reply.length > 16_384) break
            }

            val status = reply.split("\r\n").firstOrNull().orEmpty()
            if (status.contains("200")) System.currentTimeMillis() - start else -1L
        } catch (e: Exception) {
            -1L
        } finally {
            runCatching { sslClose?.takeIf { !it.isClosed }?.close() }
            runCatching { plain?.takeIf { !it.isClosed }?.close() }
        }
    }

internal actual suspend fun httpStreamTransfer(
    url: String,
    socksPort: Int?,
    upload: Boolean,
    uploadBytes: Long,
    connectTimeoutMs: Int,
    readTimeoutMs: Int,
    onChunk: (deltaBytes: Long) -> Boolean,
): HttpTiming = withContext(Dispatchers.IO) {
    val connection = if (socksPort != null) {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", socksPort))
        URL(url).openConnection(proxy) as HttpURLConnection
    } else {
        URL(url).openConnection() as HttpURLConnection
    }
    connection.apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "ZedSecure-SpeedTest")
        setRequestProperty("Accept-Encoding", "identity")
    }

    val cancelHandle = coroutineContext[Job]?.invokeOnCompletion {
        connection.disconnectQuietly()
    }
    var bytes = 0L
    val t0 = System.nanoTime()
    try {
        val buf = ByteArray(CHUNK)
        if (upload) {
            connection.requestMethod = "POST"
            connection.doOutput = true

            connection.setFixedLengthStreamingMode(uploadBytes)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.outputStream.use { os ->
                while (bytes < uploadBytes) {
                    val n = minOf(buf.size.toLong(), uploadBytes - bytes).toInt()
                    os.write(buf, 0, n)
                    bytes += n

                    if (!onChunk(n.toLong())) break
                }
                os.flush()
            }
        }

        val code = connection.responseCode
        val ttfb = System.nanoTime() - t0
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        connection.inputStream.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (!upload) {
                    bytes += n
                    if (!onChunk(n.toLong())) break
                }
            }
        }
        val wall = System.nanoTime() - t0
        val server = parseServerTimingMillis(connection.getHeaderField("server-timing"))
        HttpTiming(bytes = bytes, wallNanos = wall, ttfbNanos = ttfb, serverMillis = server)
    } catch (e: NullPointerException) {
        ensureActive()
        throw platformHttpFailure(e)
    } finally {
        cancelHandle?.dispose()
        connection.disconnectQuietly()
    }
}

private const val CHUNK = 64 * 1024
