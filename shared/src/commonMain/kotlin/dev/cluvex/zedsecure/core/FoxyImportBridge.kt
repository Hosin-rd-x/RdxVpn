package dev.cluvex.zedsecure.core

/**
 * One Mozilla VPN relay picked up from the Firefox server list, reduced to what a
 * regular server entry needs.
 */
data class FoxyRelay(
    val hostname: String,
    val host: String,
    val port: Int,
    val countryCode: String,
    val countryName: String,
    val cityName: String,
)

/**
 * Installed by the Android layer, which owns the FxA session and the Mozilla
 * Remote-Settings client (dev.cluvex.zedsecure.foxy). The servers screen calls
 * [fetch] when the user adds a Firefox tunnel; it stays null on platforms
 * without the embedded engine.
 */
object FoxyImportBridge {
    var fetch: (suspend () -> List<FoxyRelay>)? = null
}
