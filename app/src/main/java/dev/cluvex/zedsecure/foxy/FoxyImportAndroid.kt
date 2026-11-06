package dev.cluvex.zedsecure.foxy

import android.content.Context
import dev.cluvex.zedsecure.core.FoxyImportBridge
import dev.cluvex.zedsecure.core.FoxyRelay
import dev.cluvex.zedsecure.foxy.data.AppLogger
import dev.cluvex.zedsecure.foxy.data.FxaAuthRepository
import dev.cluvex.zedsecure.foxy.data.ServerListClient
import dev.cluvex.zedsecure.foxy.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "FoxyImportAndroid"

/**
 * Wires the embedded Firefox-VPN engine into [FoxyImportBridge]: a silent FxA
 * sign-in ([FxaAuthRepository.restoreSession]) followed by the Mozilla
 * Remote-Settings server list, reduced to relays the servers screen inserts as
 * regular profiles (source `foxy`).
 */
object FoxyImportAndroid {

    fun install(context: Context) {
        val appContext = context.applicationContext
        FoxyImportBridge.fetch = { importRelays(appContext) }
    }

    private suspend fun importRelays(context: Context): List<FoxyRelay> =
        withContext(Dispatchers.IO) {
            runCatching { FoxyRuntime.init(context) }
            val store = TokenStore(context)
            runCatching { FxaAuthRepository.igniteSilentSession(store, context) }
            // The server list itself is a public Mozilla endpoint and needs
            // no session, so a dead session must not block the import — but
            // it must not stay silent either: log it, so a later connect
            // failure can be traced back here instead of looking like an
            // import bug. NOTE: this repository gets the appContext on
            // purpose; without it the 406 retry loop skips the headless
            // WebView solver and only the HTTP solver runs.
            val session = runCatching { FxaAuthRepository(store, appContext = context).restoreSession() }
                .onFailure { AppLogger.w(TAG, "Firefox import: session restore threw", it) }
                .getOrNull()
            AppLogger.i(TAG, "Firefox import: session status=$session")
            val countries = ServerListClient().fetchCountries()
            val relays = countries.flatMap { country ->
                country.cities.flatMap { city ->
                    city.servers.mapNotNull { server ->
                        if (server.quarantined) return@mapNotNull null
                        val target = ServerListClient.defaultConnectTarget(server)
                            ?: return@mapNotNull null
                        FoxyRelay(
                            hostname = server.hostname.ifBlank { target.first },
                            host = target.first,
                            port = target.second,
                            countryCode = country.code,
                            countryName = country.name,
                            cityName = city.name,
                        )
                    }
                }
            }
            AppLogger.i(TAG, "Firefox import: fetched ${relays.size} relays (session=$session)")
            relays
        }
}
