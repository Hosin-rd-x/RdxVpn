package dev.cluvex.zedsecure.foxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dev.cluvex.zedsecure.foxy.data.AppLogger
import dev.cluvex.zedsecure.foxy.data.CrashReporter
import dev.cluvex.zedsecure.foxy.vpn.upstream.NettyLoggingBridge
import org.conscrypt.Conscrypt
import java.security.Security

/**
 * Replaces FoxyVPN's Application class for the embedded engine: installs the
 * crash reporter, Conscrypt provider and Netty logging bridge, and creates the
 * notification channel the Foxy service posts to.
 */
object FoxyRuntime {

    const val VPN_NOTIFICATION_CHANNEL_ID = "foxyvpn_status"

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        runCatching { CrashReporter.install(appContext) }
        runCatching { NettyLoggingBridge.install() }
        runCatching { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
            .onFailure { AppLogger.w("FoxyRuntime", "failed to install Conscrypt security provider", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                "VPN status",
                NotificationManager.IMPORTANCE_LOW,
            )
            appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        initialized = true
    }
}
