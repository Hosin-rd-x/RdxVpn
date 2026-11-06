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

/**
 * The Firefox server-list fetch failed in a way the user should see, not a
 * silent generic toast. [statusCode] is the HTTP status from Mozilla's
 * Remote-Settings endpoint when one was returned (406/403 there means the
 * Fastly bot wall is rejecting this network, the same wall that used to
 * block the FxA token refresh before 3.8.13; null means the request never
 * got an answer, e.g. no network).
 */
class FoxyImportFailure(message: String, val statusCode: Int? = null) : Exception(message)
