package dev.cluvex.zedsecure.foxy

import android.content.Context
import dev.cluvex.zedsecure.core.FoxyImportBridge
import dev.cluvex.zedsecure.core.FoxyRelay
import dev.cluvex.zedsecure.foxy.data.AppLogger
import dev.cluvex.zedsecure.foxy.data.FxaAuthRepository
import dev.cluvex.zedsecure.foxy.data.ServerListClient
import dev.cluvex.zedsecure.foxy.data.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "FoxyImportAndroid"

/**
 * Wires the embedded Firefox-VPN engine into [FoxyImportBridge]: the Mozilla
 * Remote-Settings server list, reduced to relays the servers screen inserts
 * as regular profiles (source `foxy`). The list endpoint is public and needs
 * no session, so it is fetched first and returned fast; the FxA session is
 * only warmed up in the background (a tunnel connect ignites it again when
 * it actually needs one — see FoxyVpnService).
 */
object FoxyImportAndroid {

    // Warm-up work must never block the import: it runs here, fire-and-
    // forget, on its own scope — never as a child of the import call.
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun install(context: Context) {
        val appContext = context.applicationContext
        FoxyImportBridge.fetch = { importRelays(appContext) }
    }

    private suspend fun importRelays(context: Context): List<FoxyRelay> =
        withContext(Dispatchers.IO) {
            runCatching { FoxyRuntime.init(context) }
            // The FxA session used to be refreshed here, twice in a row
            // (igniteSilentSession, then restoreSession), before the list
            // fetch even started — and each refresh can spend up to a
            // minute in the Fastly challenge solvers. With no progress
            // shown, a tap looked dead; later taps were swallowed by the
            // importing guard; and the relays landed together with
            // whatever the user tapped next. So the session is warmed up
            // in the background now, in parallel with the list fetch,
            // and the import no longer waits for it.
            warmupScope.launch {
                val store = TokenStore(context)
                runCatching { FxaAuthRepository.igniteSilentSession(store, context) }
                runCatching { FxaAuthRepository(store, appContext = context).restoreSession() }
                    .onFailure { AppLogger.w(TAG, "Firefox import: background session warm-up threw", it) }
                    .onSuccess { AppLogger.i(TAG, "Firefox import: background session status=$it") }
            }
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
            AppLogger.i(TAG, "Firefox import: fetched ${relays.size} relays")
            relays
        }
}
