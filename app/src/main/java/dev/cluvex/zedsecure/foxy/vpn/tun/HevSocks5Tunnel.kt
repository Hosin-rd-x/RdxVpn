package dev.cluvex.zedsecure.foxy.vpn.tun

import dev.cluvex.zedsecure.foxy.data.AppLogger
import hev.htproxy.TProxyService

private const val TAG = "HevSocks5Tunnel"

object HevSocks5Tunnel {
    fun start(configPath: String, tunFd: Int): Boolean =
        runCatching { TProxyService.TProxyStartService(configPath, tunFd) }
            .onFailure { AppLogger.e(TAG, "failed to start hev-socks5-tunnel", it) }
            .getOrDefault(false)

    fun stop(): Boolean =
        runCatching { TProxyService.TProxyStopService() }
            .onFailure { AppLogger.w(TAG, "failed to stop hev-socks5-tunnel", it) }
            .getOrDefault(false)

    fun isRunning(): Boolean = runCatching { TProxyService.TProxyIsRunning() }.getOrDefault(false)

    fun stats(): LongArray = runCatching { TProxyService.TProxyGetStats() ?: LongArray(4) }.getOrDefault(LongArray(4))
}
