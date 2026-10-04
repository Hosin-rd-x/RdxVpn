package dev.cluvex.zedsecure.foxy

import android.content.Context
import dev.cluvex.zedsecure.core.FoxyImportBridge
import dev.cluvex.zedsecure.core.FoxyRelay
import dev.cluvex.zedsecure.foxy.data.FxaAuthRepository
import dev.cluvex.zedsecure.foxy.data.ServerListClient
import dev.cluvex.zedsecure.foxy.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            runCatching { FxaAuthRepository.igniteSilentSession(store) }
            runCatching { FxaAuthRepository(store).restoreSession() }
            val countries = ServerListClient().fetchCountries()
            countries.flatMap { country ->
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
        }
}
