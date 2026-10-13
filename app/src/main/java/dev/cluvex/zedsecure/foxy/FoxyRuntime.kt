package dev.cluvex.zedsecure.foxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dev.cluvex.zedsecure.R
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

    const val VPN_NOTIFICATION_CHANNEL_ID = "rdx_vpn_foxy_status"

    /** Pre-3.3.7 id, whose channel name was hardcoded English "VPN status". */
    private const val LEGACY_VPN_CHANNEL_ID = "foxyvpn_status"

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
            val manager = appContext.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                VPN_NOTIFICATION_CHANNEL_ID,
                appContext.getString(R.string.notif_channel_foxy),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = appContext.getString(R.string.notif_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager?.createNotificationChannel(channel)
            runCatching { manager?.deleteNotificationChannel(LEGACY_VPN_CHANNEL_ID) }
        }
        initialized = true
    }
}
