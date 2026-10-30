package dev.cluvex.zedsecure.push

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import dev.cluvex.zedsecure.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * FCM token registration. Best-effort only: every step is guarded so a
 * device without Play Services, a missing Firebase config, or an
 * unreachable server never crashes the app and never delays startup.
 * Such devices simply receive no push notifications, by design.
 *
 * The token is uploaded only when it differs from the last uploaded one,
 * which is remembered in the existing settings storage.
 */
object FcmRegistrar {
    const val REGISTER_URL = "https://shop.hosinrdx.ir/fcm/register"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Launches the token sync in the background and returns immediately. */
    fun sync(context: Context, settingsRepository: SettingsRepository) {
        runCatching {
            scope.launch {
                runCatching { syncNow(context.applicationContext, settingsRepository) }
            }
        }
    }

    /** Fetches the token and uploads it only when it changed. Silent on any failure. */
    suspend fun syncNow(context: Context, settingsRepository: SettingsRepository) {
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return
        uploadIfChanged(context, settingsRepository, token)
    }

    /** Uploads [token] when it differs from the last uploaded one. Silent on any failure. */
    suspend fun uploadIfChanged(
        context: Context,
        settingsRepository: SettingsRepository,
        token: String,
    ) {
        if (token.isBlank()) return
        if (settingsRepository.settings.value.fcmUploadedToken == token) return
        if (uploadToken(token)) {
            runCatching { settingsRepository.update { it.copy(fcmUploadedToken = token) } }
        }
    }

    private suspend fun uploadToken(token: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(REGISTER_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.outputStream.use {
                    it.write(JSONObject().put("token", token).toString().toByteArray(Charsets.UTF_8))
                }
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }
}
