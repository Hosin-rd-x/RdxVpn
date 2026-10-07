package dev.cluvex.zedsecure.core

import android.content.Context
import android.content.Intent
import dev.cluvex.zedsecure.R
import dev.cluvex.zedsecure.domain.config.SstpProfile
import kittoku.osc.DEFAULT_PREFS_NAME
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setBooleanPrefValue
import kittoku.osc.preference.accessor.setIntPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import kittoku.osc.preference.importProfile
import kittoku.osc.service.SstpVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hands a profile to the vendored Open-SSTP engine (kittoku.osc, MIT) and
 * watches it: the engine owns the tunnel, we own the connection status.
 * Both sides read and write the very same preferences file.
 */
object SstpBridge {
    private const val ACTION_CONNECT = "kittoku.osc.connect"
    private const val ACTION_DISCONNECT = "kittoku.osc.disconnect"

    private const val CONNECT_TIMEOUT_MS = 45_000L
    private const val POLL_MS = 500L

    /** Writes the profile (seeding every engine default) and asks to connect. */
    fun start(context: Context, profile: SstpProfile) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(DEFAULT_PREFS_NAME, Context.MODE_PRIVATE)

        importProfile(null, prefs)
        setStringPrefValue(profile.server, OscPrefKey.HOME_HOSTNAME, prefs)
        setStringPrefValue(profile.username, OscPrefKey.HOME_USERNAME, prefs)
        setStringPrefValue(profile.password, OscPrefKey.HOME_PASSWORD, prefs)
        setIntPrefValue(profile.port, OscPrefKey.SSL_PORT, prefs)
        if (profile.sni.isNotBlank()) {
            setStringPrefValue(profile.sni, OscPrefKey.SSL_CUSTOM_SNI, prefs)
            setBooleanPrefValue(true, OscPrefKey.SSL_DO_USE_CUSTOM_SNI, prefs)
        }

        VpnManager.setActiveKind(VpnManager.KIND_SSTP)
        app.startService(
            Intent(app, SstpVpnService::class.java).setAction(ACTION_CONNECT),
        )
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        runCatching {
            app.startService(
                Intent(app, SstpVpnService::class.java).setAction(ACTION_DISCONNECT),
            )
        }
    }

    /** The engine writes a non-empty status block once PPP is up. */
    fun status(context: Context): String = runCatching {
        val prefs = context.applicationContext
            .getSharedPreferences(DEFAULT_PREFS_NAME, Context.MODE_PRIVATE)
        getStringPrefValue(OscPrefKey.HOME_STATUS, prefs)
    }.getOrDefault("")

    /** Follows the engine until it reports up, drops, or runs out of time. */
    fun watch(scope: CoroutineScope, context: Context, remark: String) {
        scope.launch {
            var up = false
            val deadline = now() + CONNECT_TIMEOUT_MS

            while (true) {
                val status = status(context)

                if (!up && status.isNotBlank()) {
                    up = true
                    VpnManager.onConnected(remark)
                } else if (up && status.isBlank()) {
                    VpnManager.onDisconnected()
                    return@launch
                }

                if (!up && now() > deadline) {
                    stop(context)
                    VpnManager.onError(context.getString(R.string.sstp_timeout))
                    return@launch
                }

                delay(POLL_MS)
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}
