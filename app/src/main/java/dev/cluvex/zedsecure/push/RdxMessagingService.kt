package dev.cluvex.zedsecure.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.cluvex.zedsecure.MainActivity
import dev.cluvex.zedsecure.R
import dev.cluvex.zedsecure.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM push announcements. Two display paths exist by design:
 * - App in the foreground: [onMessageReceived] runs and posts the
 *   notification below.
 * - App in the background or killed: FCM displays the notification
 *   payload itself (default icon + channel from the manifest metadata);
 *   [onMessageReceived] is not called, so nothing is shown twice.
 */
class RdxMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"]
        val body = message.notification?.body ?: message.data["body"]
        if (title.isNullOrBlank() && body.isNullOrBlank()) return
        runCatching { RdxPushNotifications.show(this, title, body) }
    }

    override fun onNewToken(token: String) {
        runCatching {
            val repo = SettingsRepository(applicationContext)
            scope.launch {
                runCatching { FcmRegistrar.uploadIfChanged(applicationContext, repo, token) }
            }
        }
    }
}

object RdxPushNotifications {
    const val CHANNEL_ID = "rdx_announcements"

    // Kept far away from the VPN foreground IDs (1-4) so cancelling a
    // tunnel notification can never dismiss an announcement, and back.
    const val NOTIFICATION_ID = 9101

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.fcm_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.fcm_channel_desc)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun show(context: Context, title: String?, body: String?) {
        createChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_x)
            .setColor(BRAND)
            .setContentTitle(title?.ifBlank { context.getString(R.string.app_name) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private const val BRAND = 0xFF5646D6.toInt()
}
